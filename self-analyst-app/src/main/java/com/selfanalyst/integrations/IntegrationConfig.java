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
            if (!"streamable-http".equals(transport)) throw new IllegalArgumentException("unsupported_transport");
            endpoint = PublicEndpointPolicy.validate(endpoint).toASCIIString();
            if (!Set.of("anonymous", "bearer").contains(authType)) throw new IllegalArgumentException("unsupported_auth");
            validateRef(credentialRef); validateStorageMode(storageMode);
            if (credentialRef != null && !credentialRef.startsWith(storageMode + ":"))
                throw new IllegalArgumentException("credential_storage_mismatch");
            if ("anonymous".equals(authType) && credentialRef != null) throw new IllegalArgumentException("unexpected_credential");
            if (budget == null) throw new IllegalArgumentException("missing_budget");
        }
        public CredentialTarget credentialTarget() { return CredentialTarget.mcp(id, endpoint); }
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
            if (!Set.of("qq", "163").contains(provider)) throw new IllegalArgumentException("unsupported_provider");
            text(email, 254, "invalid_email");
            email = email.toLowerCase(Locale.ROOT);
            String domain = provider.equals("qq") ? "qq.com" : "163.com";
            if (!email.matches("[a-z0-9][a-z0-9._+\\-]{0,63}@" + domain.replace(".", "\\.")))
                throw new IllegalArgumentException("invalid_email");
            if (!"all".equals(folderScope) && !"selected".equals(folderScope))
                throw new IllegalArgumentException("invalid_folder_scope");
            allowedFolders = List.copyOf(allowedFolders);
            if (("selected".equals(folderScope) && allowedFolders.isEmpty()) || allowedFolders.size() > 32
                    || new HashSet<>(allowedFolders).size() != allowedFolders.size())
                throw new IllegalArgumentException("invalid_folders");
            for (String folder : allowedFolders) text(folder, 256, "invalid_folder");
            validateRef(credentialRef); validateStorageMode(storageMode);
            if (credentialRef != null && !credentialRef.startsWith(storageMode + ":"))
                throw new IllegalArgumentException("credential_storage_mismatch");
            if (disclosureVersion < 0 || disclosureVersion > 1) throw new IllegalArgumentException("invalid_disclosure_version");
            if (enabled && disclosureVersion != 1) throw new IllegalArgumentException("disclosure_required");
        }
        public String host() { return provider.equals("qq") ? "imap.qq.com" : "imap.163.com"; }
        public int port() { return 993; }
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
