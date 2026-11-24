package com.selfanalyst.mcp;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.selfanalyst.integrations.CredentialStore;
import com.selfanalyst.integrations.CredentialTarget;
import com.selfanalyst.integrations.IntegrationConfig;
import com.selfanalyst.mail.MailAccount;
import com.selfanalyst.mail.MailCancellation;
import com.selfanalyst.mail.MailException;
import com.selfanalyst.mail.MailWriteService;
import com.selfanalyst.mail.ReadOnlyMailService;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Explicit account-scoped sharing. The localhost token never authorizes a mailbox mutation by itself. */
public final class McpMailManagement implements LocalMcpHttpEndpoint.Access, AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();
    private static final CredentialTarget TOKEN_TARGET = new CredentialTarget("selfanalyst-local-mcp", "selfanalyst:localhost-mcp");
    private static final int MAX_BYTES = 256 * 1024;
    public record AccountSetting(String id, boolean expose, boolean allowDraft, boolean allowSend) { }
    private record Rule(String id, String binding, boolean expose, boolean allowDraft,
                        boolean allowSend, String smtpRef, String smtpRecipient) { }
    private record Saved(long revision, String tokenRef, List<Rule> rules) { }
    private record Grant(Saved state, long integrationRevision, String route) { }
    private record Requested(String id, String operation, String account, List<String> to,
                             String subject, String body, CompletableFuture<Boolean> decision, Grant grant) {
        Map<String, Object> view() {
            return Map.of("id", id, "operation", operation, "account", account, "to", to, "subject", subject, "body", body);
        }
    }
    private final Path file;
    private final Supplier<IntegrationConfig> config;
    private final CredentialStore vault;
    private final BooleanSupplier persistentSupported;
    private final ReadOnlyMailService reader;
    private final MailWriteService writer;
    private final Map<String, Grant> invocations = new ConcurrentHashMap<>();
    private final Map<String, Requested> pending = new ConcurrentHashMap<>();
    private final Semaphore capacity = new Semaphore(8);
    private volatile Saved saved;
    private volatile boolean closed;

    public McpMailManagement(Path root, Supplier<IntegrationConfig> config, CredentialStore vault,
                             BooleanSupplier persistentSupported) {
        this(root, config, vault, persistentSupported, new MailWriteService((host, port) -> {
            var pinned = new com.selfanalyst.integrations.PublicEndpointPolicy().resolve("https://" + host + ":" + port);
            return new java.net.InetSocketAddress(pinned.addresses().getFirst(), port);
        }));
    }

    McpMailManagement(Path root, Supplier<IntegrationConfig> config, CredentialStore vault,
                      BooleanSupplier persistentSupported, MailWriteService writer) {
        this.file = root.toAbsolutePath().normalize().resolve("config/local-mcp.json");
        this.config = config;
        this.vault = vault;
        this.persistentSupported = persistentSupported;
        this.writer = writer;
        this.saved = read();
        this.reader = new ReadOnlyMailService(this::mailAccounts, this::imapSecret, new ReadOnlyMailService.AuthorizationBoundary() {
            @Override
            public void authorize(ReadOnlyMailService.CallContext context, MailAccount account, String folder, boolean body) {
                Grant grant = invocations.get(context.turnId());
                if (grant == null || !live(grant) || !inScope(grant, account.id()))
                    throw new MailException(MailException.Code.ACCESS_DENIED);
            }
            @Override
            public void beforeResult(ReadOnlyMailService.CallContext context, MailAccount account) {
                authorize(context, account, null, false);
            }
        });
    }

    private Saved read() {
        try {
            safePath();
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return new Saved(0, null, List.of());
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = input.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) return null;
                Saved value = JSON.readValue(bytes, Saved.class);
                if (value == null || value.revision() < 0 || value.rules() == null || value.rules().size() > 64
                        || !ref(value.tokenRef()) || value.rules().stream().anyMatch(rule -> rule == null
                        || rule.id() == null || !rule.id().matches("[A-Za-z0-9_-]{1,80}") || rule.id().equals("all")
                        || rule.binding() == null || !rule.binding().matches("[a-f0-9]{64}") || !ref(rule.smtpRef())
                        || (rule.smtpRef() == null) != (rule.smtpRecipient() == null)
                        || rule.smtpRecipient() != null && !smtpRecipient(rule.smtpRecipient()))
                        || value.rules().stream().map(Rule::id).distinct().count() != value.rules().size()) return null;
                return new Saved(value.revision(), value.tokenRef(), List.copyOf(value.rules()));
            }
        } catch (IOException | RuntimeException unavailable) {
            return null;
        }
    }

    private static boolean ref(String value) {
        return value == null || value.matches("windows:[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    }

    private static boolean smtpRecipient(String value) {
        return value.length() < 512 && value.matches("smtps://smtp\\.(qq\\.com|163\\.com|126\\.com|gmail\\.com):465/[^\\p{Cntrl}]+");
    }

    private void safePath() throws IOException {
        for (Path path = file; path != null; path = path.getParent())
            if (Files.isSymbolicLink(path)) throw new IOException("unsafe_configuration_path");
    }

    private void persist(Saved next) throws IOException {
        safePath();
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(".local-mcp-" + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer content = ByteBuffer.wrap(JSON.writeValueAsBytes(next));
                while (content.hasRemaining()) channel.write(content);
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            saved = next;
            pending.values().forEach(request -> request.decision().complete(false));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public Map<String, Object> status(int port) {
        Saved current = saved;
        var accounts = new ArrayList<Map<String, Object>>();
        for (var account : config.get().mailAccounts()) {
            if (account.id().equals("all")) continue;
            Rule rule = rule(current, account);
            accounts.add(Map.of("id", account.id(), "label", account.displayName(), "enabled", account.enabled(),
                    "expose", rule != null && rule.expose(), "allowDraft", rule != null && rule.allowDraft(),
                    "allowSend", rule != null && rule.allowSend(),
                    "smtpConfigured", rule != null && vault.configured(rule.smtpRef(), smtpTarget(account))));
        }
        return Map.of("revision", current == null ? -1L : current.revision(), "accounts", accounts,
                "tokenConfigured", current != null && vault.configured(current.tokenRef(), TOKEN_TARGET),
                "persistentSupported", persistentSupported.getAsBoolean(), "baseUrl", "http://127.0.0.1:" + port,
                "pending", pending.values().stream().filter(value -> live(value.grant())).map(Requested::view).toList(),
                "configurationAvailable", current != null);
    }

    public synchronized void save(long revision, List<AccountSetting> settings) throws IOException {
        Saved original = requireRevision(revision);
        var integration = config.get();
        if (settings == null || settings.size() > 64) throw new IOException("invalid_settings");
        var accounts = new LinkedHashMap<String, Rule>();
        for (AccountSetting setting : settings) {
            if (setting == null || setting.id() == null || setting.id().equals("all") || accounts.containsKey(setting.id()))
                throw new IOException("invalid_settings");
            var account = find(integration, setting.id());
            if ((setting.expose() || setting.allowDraft() || setting.allowSend()) && !account.enabled())
                throw new IOException("account_disabled");
            Rule existing = rule(original, account);
            if (setting.allowSend() && (existing == null || !vault.configured(existing.smtpRef(), smtpTarget(account))))
                throw new IOException("smtp_credential_required");
            accounts.put(account.id(), new Rule(account.id(), binding(account), setting.expose(),
                    setting.allowDraft(), setting.allowSend(), existing == null ? null : existing.smtpRef(),
                    existing == null ? null : existing.smtpRecipient()));
        }
        if (config.get().revision() != integration.revision()) throw new IOException("revision_conflict");
        // Sending is not enabled implicitly by storing a credential.
        persist(new Saved(revision + 1, original.tokenRef(), List.copyOf(accounts.values())));
        retireRules(original, accounts.values());
    }

    public synchronized String rotate(long revision) throws IOException {
        Saved original = requireRevision(revision);
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Arrays.fill(bytes, (byte) 0);
        char[] secret = token.toCharArray();
        String newRef = null;
        boolean committed = false;
        try {
            newRef = secureWrite(TOKEN_TARGET, secret);
            persist(new Saved(revision + 1, newRef, original.rules()));
            committed = true;
            retire(original.tokenRef(), TOKEN_TARGET);
            return token;
        } finally {
            Arrays.fill(secret, '\0');
            if (!committed) retire(newRef, TOKEN_TARGET);
        }
    }

    public synchronized void smtp(long revision, String id, char[] secret) throws IOException {
        String newRef = null;
        CredentialTarget target = null;
        boolean committed = false;
        try {
            Saved original = requireRevision(revision);
            var integration = config.get();
            var account = find(integration, id);
            if (id.equals("all") || !account.enabled()) throw new IOException("account_disabled");
            if (!account.profile().acceptsAuthorizationCode(secret)) throw new IOException("invalid_credential");
            Rule existing = rule(original, account);
            target = smtpTarget(account);
            newRef = secureWrite(target, secret);
            if (config.get().revision() != integration.revision()) throw new IOException("revision_conflict");
            var rules = new ArrayList<>(original.rules());
            rules.removeIf(rule -> rule.id().equals(id));
            rules.add(new Rule(id, binding(account), existing != null && existing.expose(),
                    existing != null && existing.allowDraft(), false, newRef, target.recipient()));
            persist(new Saved(revision + 1, original.tokenRef(), List.copyOf(rules)));
            committed = true;
            retireRules(original, rules);
        } finally {
            Arrays.fill(secret, '\0');
            if (!committed) retire(newRef, target);
        }
    }

    /** The connector store can revoke or replace an account independently of the sharing UI. */
    synchronized void sourceChanged() {
        pending.values().forEach(request -> request.decision().complete(false));
        Saved prior = saved;
        if (closed || prior == null || prior.rules().isEmpty()) return;
        var integration = config.get();
        var remaining = prior.rules().stream()
                .filter(rule -> integration.mailAccounts().stream().anyMatch(account -> account.enabled()
                        && rule.id().equals(account.id()) && rule.binding().equals(binding(account))))
                .toList();
        if (remaining.size() == prior.rules().size()) return;
        try {
            persist(new Saved(Math.incrementExact(prior.revision()), prior.tokenRef(), remaining));
            retireRules(prior, remaining);
        } catch (IOException | ArithmeticException unavailable) {
            // Do not keep using a stale authorization or unlink a possibly still referenced secret.
            saved = null;
        }
    }

    private Saved requireRevision(long revision) throws IOException {
        Saved original = saved;
        if (closed || original == null) throw new IOException("configuration_unavailable");
        if (original.revision() != revision || revision == Long.MAX_VALUE) throw new IOException("revision_conflict");
        return original;
    }

    private String secureWrite(CredentialTarget target, char[] secret) throws IOException {
        if (!persistentSupported.getAsBoolean()) throw new IOException("persistent_credentials_unavailable");
        return vault.write(target, secret, "windows");
    }

    private void retireRules(Saved prior, java.util.Collection<Rule> current) {
        for (Rule old : prior.rules())
            if (old.smtpRef() != null && current.stream().noneMatch(rule -> old.smtpRef().equals(rule.smtpRef())))
                retire(old.smtpRef(), new CredentialTarget(old.id(), old.smtpRecipient()));
    }

    private void retire(String ref, CredentialTarget target) {
        if (ref == null || target == null) return;
        try {
            if (vault.configured(ref, target)) vault.delete(ref);
        } catch (IOException ignored) { /* vault retains its retry state */ }
    }

    private static IntegrationConfig.MailAccount find(IntegrationConfig cfg, String id) throws IOException {
        return cfg.mailAccounts().stream().filter(account -> account.id().equals(id)).findFirst()
                .orElseThrow(() -> new IOException("account_not_found"));
    }

    private static CredentialTarget smtpTarget(IntegrationConfig.MailAccount account) {
        return CredentialTarget.mailSmtp(account.id(), account.provider(), account.email());
    }

    private static String binding(IntegrationConfig.MailAccount account) {
        return hex(JSON.createObjectNode().put("id", account.id()).put("provider", account.provider())
                .put("email", account.email()).put("credentialRef", account.credentialRef())
                .put("enabled", account.enabled()).put("bodyAccess", account.bodyAccess())
                .put("folderScope", account.folderScope())
                .set("folders", JSON.valueToTree(account.allowedFolders().stream().sorted().toList())).toString());
    }

    private static String hex(String text) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Rule rule(Saved state, IntegrationConfig.MailAccount account) {
        return state == null ? null : state.rules().stream()
                .filter(rule -> rule.id().equals(account.id()) && rule.binding().equals(binding(account)))
                .findFirst().orElse(null);
    }

    private List<MailAccount> mailAccounts() {
        var cfg = config.get();
        return cfg.mailAccounts().stream().map(account -> asMail(account, cfg.revision())).toList();
    }

    static MailAccount asMail(IntegrationConfig.MailAccount account, long revision) {
        return new MailAccount(account.id(), account.profile(),
                account.email(), account.displayName(), account.enabled(), account.folderScope(),
                Set.copyOf(account.allowedFolders()), account.bodyAccess(), revision);
    }

    private char[] imapSecret(MailAccount account) {
        try {
            var integration = config.get();
            var row = find(integration, account.id());
            if (!account.equals(asMail(row, integration.revision())) || !row.enabled()) return null;
            return vault.read(row.credentialRef(), row.credentialTarget()).orElse(null);
        } catch (IOException absent) { return null; }
    }

    @Override
    public boolean authorize(String serverId, String token) {
        Saved current = saved;
        if (closed || current == null || current.tokenRef() == null || token == null
                || !token.matches("[A-Za-z0-9_-]{43}")) return false;
        byte[] supplied = token.getBytes(StandardCharsets.US_ASCII);
        byte[] expected = null;
        char[] secret = null;
        try {
            secret = vault.read(current.tokenRef(), TOKEN_TARGET).orElse(null);
            if (secret == null || secret.length != 43) return false;
            expected = new byte[secret.length];
            for (int i = 0; i < expected.length; i++) expected[i] = (byte) secret[i];
            return saved == current && !closed && MessageDigest.isEqual(supplied, expected);
        } catch (IOException unavailable) {
            return false;
        } finally {
            Arrays.fill(supplied, (byte) 0);
            if (expected != null) Arrays.fill(expected, (byte) 0);
            if (secret != null) Arrays.fill(secret, '\0');
        }
    }

    private Grant grant(String route, String token) throws IOException {
        Saved candidate = saved;
        long revision = config.get().revision();
        if (candidate == null || !authorize(route, token)) throw new IOException("access_denied");
        Grant result = new Grant(candidate, revision, route);
        if (!live(result)) throw new IOException("access_denied");
        return result;
    }

    private boolean live(Grant grant) {
        return !closed && saved == grant.state() && config.get().revision() == grant.integrationRevision();
    }

    private List<IntegrationConfig.MailAccount> accounts(Saved state, String route) {
        if (state == null || closed) return List.of();
        return config.get().mailAccounts().stream().filter(IntegrationConfig.MailAccount::enabled)
                .filter(account -> "all".equals(route) || route.equals(account.id()))
                .filter(account -> {
                    Rule rule = rule(state, account);
                    return rule != null && rule.expose();
                }).toList();
    }

    private boolean inScope(Grant grant, String id) {
        return accounts(grant.state(), grant.route()).stream().anyMatch(account -> account.id().equals(id));
    }

    @Override
    public boolean exposed(String serverId) {
        return !accounts(saved, serverId).isEmpty();
    }

    @Override
    public List<JsonNode> listTools(String serverId, String bearerToken) throws IOException {
        Grant grant = grant(serverId, bearerToken);
        var result = new ArrayList<JsonNode>();
        for (var row : accounts(grant.state(), serverId)) {
            Rule rule = rule(grant.state(), row);
            String prefix = serverId.equals("all") ? row.id() + "__" : "";
            result.add(tool(prefix + "list_folders", "List readable IMAP folders.", Map.of(), List.of()));
            result.add(tool(prefix + "search_emails", "Search bounded mail headers in a folder.",
                    Map.of("folder", string(), "subject", string(), "from", string(), "cursor", string(),
                            "after", string(), "before", string(), "unread", Map.of("type", "boolean"),
                            "pageSize", Map.of("type", "integer", "minimum", 1, "maximum", 50)), List.of()));
            if (row.bodyAccess()) result.add(tool(prefix + "read_email", "Read a previously returned message reference.",
                    Map.of("reference", string()), List.of("reference")));
            if (rule.allowDraft()) result.add(writeTool(prefix + "create_draft", "Request approval to save one remote draft."));
            if (rule.allowSend()) result.add(writeTool(prefix + "send_email", "Request approval to send one email."));
        }
        if (!live(grant)) throw new IOException("access_denied");
        return List.copyOf(result);
    }

    private static Map<String, String> string() { return Map.of("type", "string"); }

    private static JsonNode writeTool(String name, String description) {
        return tool(name, description, Map.of("to", Map.of("type", "array", "items", string()),
                "subject", string(), "body", string()), List.of("to", "subject", "body"));
    }

    private static JsonNode tool(String name, String description, Map<String, ?> props, List<String> required) {
        return JSON.valueToTree(Map.of("name", name, "description", description, "inputSchema",
                Map.of("type", "object", "properties", props, "required", required, "additionalProperties", false)));
    }

    @Override
    public JsonNode callTool(String serverId, String bearerToken, String toolName, ObjectNode arguments) throws Exception {
        Grant grant = grant(serverId, bearerToken);
        if (!capacity.tryAcquire()) throw new IOException("server_busy");
        String callId = UUID.randomUUID().toString();
        var cancellation = new MailCancellation();
        try {
            var row = accounts(grant.state(), serverId).stream()
                    .filter(account -> !serverId.equals("all") || toolName.startsWith(account.id() + "__"))
                    .max(java.util.Comparator.comparingInt(account -> account.id().length()))
                    .orElseThrow(() -> new IOException("access_denied"));
            String name = serverId.equals("all") ? toolName.substring(row.id().length() + 2) : toolName;
            // Keep search references scoped to the authorized account, route and token generation.
            String session = "local-mcp:" + hex(serverId + "\n" + row.id() + "\n" + grant.state().revision());
            var ctx = new ReadOnlyMailService.CallContext(session, callId, cancellation);
            invocations.put(callId, grant);
            Object result = switch (name) {
                case "list_folders" -> {
                    fields(arguments, Set.of());
                    yield reader.listFolders(row.id(), ctx);
                }
                case "search_emails" -> {
                    fields(arguments, Set.of("folder", "subject", "from", "cursor", "after", "before", "unread", "pageSize"));
                    yield reader.searchMessages(new ReadOnlyMailService.SearchRequest(row.id(), optional(arguments, "folder"),
                            instant(arguments, "after"), instant(arguments, "before"), optional(arguments, "from"),
                            optional(arguments, "subject"), booleanValue(arguments, "unread"), pageSize(arguments),
                            optional(arguments, "cursor")), ctx);
                }
                case "read_email" -> {
                    fields(arguments, Set.of("reference"));
                    if (!row.bodyAccess()) throw new IOException("access_denied");
                    yield reader.getMessage(required(arguments, "reference"), ctx);
                }
                case "create_draft", "send_email" -> externalWrite(grant, row, name, arguments, cancellation);
                default -> throw new IOException("tool_not_available");
            };
            if (!name.equals("create_draft") && !name.equals("send_email") && !live(grant))
                throw new IOException("access_denied");
            return JSON.valueToTree(Map.of("content", List.of(Map.of("type", "text", "text", JSON.writeValueAsString(result))), "isError", false));
        } finally {
            invocations.remove(callId);
            cancellation.cancel();
            capacity.release();
        }
    }

    private Object externalWrite(Grant grant, IntegrationConfig.MailAccount row, String name, ObjectNode args,
                                 MailCancellation cancellation) throws Exception {
        String id = UUID.randomUUID().toString();
        var prepared = prepare(grant.state(), grant.integrationRevision(), row, name, args);
        var details = JSON.valueToTree(prepared.approvalDetails());
        var approvedRecipients = new ArrayList<String>();
        details.path("to").forEach(recipient -> approvedRecipients.add(recipient.textValue()));
        Requested request = new Requested(id, name, details.path("from").textValue(), List.copyOf(approvedRecipients),
                details.path("subject").textValue(), details.path("body").textValue(), new CompletableFuture<>(), grant);
        pending.put(id, request);
        try {
            if (!live(grant) || !request.decision().get(60, TimeUnit.SECONDS) || !live(grant)) throw new IOException("approval_denied");
            return execute(grant.state(), grant.integrationRevision(), row, name, prepared, () -> live(grant), cancellation);
        } finally {
            pending.remove(id);
        }
    }

    public boolean decide(String id, boolean allowed) {
        Requested request = pending.get(id);
        return request != null && live(request.grant()) && request.decision().complete(allowed);
    }

    long writeRevision() {
        var state = saved;
        return state == null ? -1 : state.revision();
    }

    boolean writeAvailable(IntegrationConfig.MailAccount account, String tool) {
        Rule rule = rule(saved, account);
        return !closed && account.enabled() && rule != null && switch (tool) {
            case "create_draft" -> rule.allowDraft();
            case "send_email" -> rule.allowSend();
            default -> false;
        };
    }

    Object internalWrite(long expected, String accountId, String tool, Map<String, Object> arguments, MailCancellation cancellation,
                         BooleanSupplier active, java.util.function.Consumer<MailWriteService.Prepared> approval) throws Exception {
        var cfg = config.get();
        Saved state = saved;
        if (state == null || state.revision() != expected || closed) throw new IOException("access_denied");
        var row = find(cfg, accountId);
        ObjectNode args = JSON.valueToTree(arguments);
        var prepared = prepare(state, cfg.revision(), row, tool, args);
        approval.accept(prepared);
        return execute(state, cfg.revision(), row, tool, prepared,
                () -> active.getAsBoolean() && !closed && saved == state && config.get().revision() == cfg.revision(),
                cancellation);
    }

    private MailWriteService.Prepared prepare(Saved state, long rev, IntegrationConfig.MailAccount row, String name,
                                              ObjectNode args) throws IOException {
        Rule rule = rule(state, row);
        boolean send = name.equals("send_email");
        if (rule == null || !row.enabled() || !(send ? rule.allowSend() : rule.allowDraft())) throw new IOException("tool_not_available");
        fields(args, Set.of("to", "subject", "body"));
        return writer.prepare(asMail(row, rev), send ? MailWriteService.Operation.SEND : MailWriteService.Operation.CREATE_DRAFT,
                recipients(args), required(args, "subject"), required(args, "body"));
    }

    private Object execute(Saved state, long rev, IntegrationConfig.MailAccount row, String name,
                           MailWriteService.Prepared prepared, BooleanSupplier authorized, MailCancellation cancellation) throws IOException {
        if (!authorized.getAsBoolean()) throw new IOException("access_denied");
        var account = asMail(row, rev);
        Rule rule = rule(state, row);
        if (rule == null) throw new IOException("access_denied");
        char[] secret = name.equals("send_email") ? vault.read(rule.smtpRef(), smtpTarget(row)).orElse(null) : imapSecret(account);
        if (secret == null) throw new IOException("credential_required");
        return writer.execute(prepared, secret, authorized, cancellation);
    }

    private static List<String> recipients(ObjectNode arguments) throws IOException {
        JsonNode values = arguments.get("to");
        if (values == null || !values.isArray() || values.size() > MailWriteService.MAX_RECIPIENTS) throw new IOException("invalid_arguments");
        var result = new ArrayList<String>();
        for (JsonNode node : values) {
            if (!node.isTextual() || node.textValue().length() > 254) throw new IOException("invalid_arguments");
            result.add(node.textValue());
        }
        return List.copyOf(result);
    }

    private static java.time.Instant instant(ObjectNode args, String field) throws IOException {
        String value = optional(args, field);
        if (value == null) return null;
        try { return java.time.Instant.parse(value); }
        catch (java.time.format.DateTimeParseException invalid) { throw new IOException("invalid_arguments"); }
    }

    private static Boolean booleanValue(ObjectNode args, String field) throws IOException {
        if (!args.has(field)) return null;
        if (!args.path(field).isBoolean()) throw new IOException("invalid_arguments");
        return args.get(field).booleanValue();
    }

    private static int pageSize(ObjectNode args) throws IOException {
        if (!args.has("pageSize")) return 20;
        var size = args.path("pageSize");
        if (!size.isIntegralNumber() || !size.canConvertToInt() || size.intValue() < 1 || size.intValue() > 50)
            throw new IOException("invalid_arguments");
        return size.intValue();
    }

    private static String optional(ObjectNode args, String field) throws IOException {
        return args.has(field) ? required(args, field) : null;
    }

    private static String required(ObjectNode args, String field) throws IOException {
        if (!args.path(field).isTextual()) throw new IOException("invalid_arguments");
        return args.get(field).textValue();
    }

    private static void fields(ObjectNode args, Set<String> names) throws IOException {
        var fields = args.fieldNames();
        while (fields.hasNext()) if (!names.contains(fields.next())) throw new IOException("invalid_arguments");
    }

    @Override
    public void close() {
        closed = true;
        pending.values().forEach(request -> request.decision().complete(false));
        reader.close();
        vault.close();
    }
}
