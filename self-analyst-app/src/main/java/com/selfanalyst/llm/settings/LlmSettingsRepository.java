package com.selfanalyst.llm.settings;

import com.selfanalyst.config.ConfigApplicationService;
import com.selfanalyst.config.ConfigResolver;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;

public interface LlmSettingsRepository {
    Object commitLock();
    Properties read() throws IOException;
    ConfigResolver.Snapshot resolve(Properties user);
    ConfigApplicationService.SaveResult save(Map<String, String> changes) throws IOException;
    Map<String, Object> runtime();
}
