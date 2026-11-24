package com.selfanalyst.integrations;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Versioned public metadata. Secrets are deliberately not representable. */
public record IntegrationConfig(int schemaVersion, long revision, List<McpServer> mcpServers,
                                List<MailAccount> mailAccounts, List<ToolApproval> toolApprovals) {
    public static final int SCHEMA_VERSION = 1;
    public static final String LEGACY_WEBSEARCH_ID = "legacy-websearch";
    private static final com.fasterxml.jackson.databind.ObjectMapper COMMAND_JSON = new com.fasterxml.jackson.databind.ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public IntegrationConfig {
        if (schemaVersion != SCHEMA_VERSION) throw new IllegalArgumentException("unsupported_schema_version");
        if (revision < 0) throw new IllegalArgumentException("invalid_revision");
        mcpServers = List.copyOf(mcpServers);
        mailAccounts = List.copyOf(mailAccounts);
        toolApprovals = List.copyOf(toolApprovals);
        if (mcpServers.size() > 32 || mailAccounts.size() > 16 || toolApprovals.size() > 1024)
            throw new IllegalArgumentException("configuration_limit");
        Set<String> ids = new HashSet<>();
        for (McpServer server : mcpServers) if (!ids.add(server.id())) throw new IllegalArgumentException("duplicate_id");
        for (MailAccount account : mailAccounts) if (!ids.add(account.id())) throw new IllegalArgumentException("duplicate_id");
        Set<String> approvals = new HashSet<>();
        for (ToolApproval approval : toolApprovals) {
            if (!approval.sourceId().equals(LEGACY_WEBSEARCH_ID)
                    && mcpServers.stream().noneMatch(s -> s.id().equals(approval.sourceId())))
                throw new IllegalArgumentException("unknown_approval_source");
            if (!approvals.add(approval.sourceId() + "\n" + approval.toolName()))
                throw new IllegalArgumentException("duplicate_approval");
            if (approval.approvedRevision() > revision) throw new IllegalArgumentException("invalid_approval_revision");
        }
    }

    public static IntegrationConfig empty() { return new IntegrationConfig(1, 0, List.of(), List.of(), List.of()); }
    public IntegrationConfig withRevision(long value) {
        return new IntegrationConfig(schemaVersion, value, mcpServers, mailAccounts, toolApprovals);
    }

    /** Portable metadata never carries authorization or machine-specific credentials. */
    public IntegrationConfig portable() {
        return new IntegrationConfig(1, 0,
                mcpServers.stream().map(s -> new McpServer(s.id(), s.name(), false, s.transport(), s.endpoint(),
                        s.authType(), null, "session", s.budget())).toList(),
                mailAccounts.stream().map(a -> new MailAccount(a.id(), a.provider(), a.email(), a.displayName(),
                        false, a.folderScope(), a.allowedFolders(), a.bodyAccess(), null, "session", 0)).toList(), List.of());
    }

    public record Budget(int connectTimeoutSeconds, int requestTimeoutSeconds, int maxTools,
                         int maxSchemaBytes, int maxResultBytes) {
        public Budget {
            range(connectTimeoutSeconds, 1, 30); range(requestTimeoutSeconds, 1, 120);
            range(maxTools, 1, 128); range(maxSchemaBytes, 256, 65536); range(maxResultBytes, 1024, 1048576);
        }
        public static Budget defaults() { return new Budget(10, 30, 64, 32768, 262144); }
    }

    public record McpServer(String id, String name, boolean enabled, String transport, String endpoint,
                            String authType, String credentialRef, String storageMode, Budget budget) {
        public McpServer {
            validateId(id); text(name, 128, "invalid_name");
            if (!Set.of("streamable-http", "sse", "stdio").contains(transport))
                throw new IllegalArgumentException("unsupported_transport");
            if ("stdio".equals(transport)) {
                if (!"anonymous".equals(authType)) throw new IllegalArgumentException("unsupported_auth");
                try { endpoint = COMMAND_JSON.writeValueAsString(stdioArguments(endpoint)); }
                catch (java.io.IOException invalid) { throw new IllegalArgumentException("invalid_stdio_command"); }
            } else {
                endpoint = PublicEndpointPolicy.validate(endpoint).toASCIIString();
            }
            if (!Set.of("anonymous", "bearer").contains(authType)) throw new IllegalArgumentException("unsupported_auth");
            validateRef(credentialRef); validateStorageMode(storageMode);
            if (credentialRef != null && !credentialRef.startsWith(storageMode + ":"))
                throw new IllegalArgumentException("credential_storage_mismatch");
            if ("anonymous".equals(authType) && credentialRef != null) throw new IllegalArgumentException("unexpected_credential");
            if (budget == null) throw new IllegalArgumentException("missing_budget");
        }
        public CredentialTarget credentialTarget() {
            if ("stdio".equals(transport)) throw new IllegalStateException("stdio_does_not_accept_credentials");
            return CredentialTarget.mcp(id, endpoint);
        }
        public List<String> argv() { return stdioArguments(endpoint); }
        public McpServer withCredential(String ref, String mode) {
            return new McpServer(id, name, enabled, transport, endpoint, authType, ref, mode, budget);
        }
        public McpServer disconnected() {
            return new McpServer(id, name, false, transport, endpoint, authType, null, "session", budget);
        }
    }

    public record MailAccount(String id, String provider, String email, String displayName, boolean enabled,
                              String folderScope, List<String> allowedFolders, boolean bodyAccess, String credentialRef,
                              String storageMode, int disclosureVersion) {
        /** Existing Java callers retain the same exact-folder authorization. */
        public MailAccount(String id, String provider, String email, String displayName, boolean enabled,
                           List<String> allowedFolders, boolean bodyAccess, String credentialRef,
                           String storageMode, int disclosureVersion) {
            this(id, provider, email, displayName, enabled, "selected", allowedFolders, bodyAccess,
                    credentialRef, storageMode, disclosureVersion);
        }
        public MailAccount {
            validateId(id); text(displayName, 128, "invalid_name");
            var profile = com.selfanalyst.mail.MailAccount.Provider.fromId(provider);
            text(email, 254, "invalid_email");
            email = profile.bindingEmail(email.toLowerCase(Locale.ROOT));
            if (!"all".equals(folderScope) && !"selected".equals(folderScope))
                throw new IllegalArgumentException("invalid_folder_scope");
            allowedFolders = List.copyOf(allowedFolders);
            if (("selected".equals(folderScope) && allowedFolders.isEmpty()) || allowedFolders.size() > 256
                    || new HashSet<>(allowedFolders).size() != allowedFolders.size())
                throw new IllegalArgumentException("invalid_folders");
            for (String folder : allowedFolders) text(folder, 256, "invalid_folder");
            validateRef(credentialRef); validateStorageMode(storageMode);
            if (credentialRef != null && !credentialRef.startsWith(storageMode + ":"))
                throw new IllegalArgumentException("credential_storage_mismatch");
            if (disclosureVersion < 0 || disclosureVersion > 1) throw new IllegalArgumentException("invalid_disclosure_version");
            if (enabled && disclosureVersion != 1) throw new IllegalArgumentException("disclosure_required");
        }
        public com.selfanalyst.mail.MailAccount.Provider profile() {
            return com.selfanalyst.mail.MailAccount.Provider.fromId(provider);
        }
        public String host() { return profile().host(); }
        public int port() { return profile().port(); }
        public CredentialTarget credentialTarget() { return CredentialTarget.mail(id, provider, email); }
        public MailAccount withCredential(String ref, String mode) {
            return new MailAccount(id, provider, email, displayName, enabled, folderScope, allowedFolders, bodyAccess, ref, mode, disclosureVersion);
        }
        public MailAccount awaitingActivation() {
            return new MailAccount(id, provider, email, displayName, false, folderScope, allowedFolders, bodyAccess,
                    credentialRef, storageMode, 0);
        }
        public MailAccount activated(String reference) {
            return new MailAccount(id, provider, email, displayName, true, folderScope, allowedFolders, bodyAccess,
                    reference, "windows", 1);
        }
        public MailAccount disconnected() {
            return new MailAccount(id, provider, email, displayName, false, folderScope, allowedFolders, bodyAccess, null, "session", disclosureVersion);
        }
    }

    public record ToolApproval(String sourceId, String toolName, String definitionDigest, long approvedRevision) {
        public ToolApproval {
            if (!LEGACY_WEBSEARCH_ID.equals(sourceId)) validateId(sourceId);
            text(toolName, 256, "invalid_tool_name");
            if (definitionDigest == null || !definitionDigest.matches("[a-f0-9]{64}") || approvedRevision < 0)
                throw new IllegalArgumentException("invalid_tool_approval");
        }
    }

    private static List<String> stdioArguments(String value) {
        if (value == null || value.length() > 8192) throw new IllegalArgumentException("invalid_stdio_command");
        try {
            var root = COMMAND_JSON.readTree(value);
            if (root == null || !root.isArray() || root.isEmpty() || root.size() > 32)
                throw new IllegalArgumentException("invalid_stdio_command");
            var result = new java.util.ArrayList<String>();
            for (var item : root) {
                if (!item.isTextual() || item.textValue().length() > 2048
                        || item.textValue().chars().anyMatch(Character::isISOControl))
                    throw new IllegalArgumentException("invalid_stdio_command");
                result.add(item.textValue());
            }
            String executable = result.getFirst();
            if (!(executable.startsWith("/") || executable.matches("[A-Za-z]:[\\\\/].+"))
                    || executable.startsWith("//") || executable.isBlank())
                throw new IllegalArgumentException("invalid_stdio_command");
            return List.copyOf(result);
        } catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("invalid_stdio_command");
        }
    }

    public static void validateId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}") || LEGACY_WEBSEARCH_ID.equals(id))
            throw new IllegalArgumentException("invalid_id");
    }
    static void validateRef(String ref) {
        if (ref != null && !ref.matches("(?:session|windows):[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            throw new IllegalArgumentException("invalid_credential_reference");
    }
    static void validateStorageMode(String mode) {
        if (!"session".equals(mode) && !"windows".equals(mode)) throw new IllegalArgumentException("unsupported_storage_mode");
    }
    static void text(String value, int max, String error) {
        if (value == null || value.isBlank() || value.length() > max || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(error);
    }
    private static void range(int value, int min, int max) {
        if (value < min || value > max) throw new IllegalArgumentException("invalid_budget");
    }
}
