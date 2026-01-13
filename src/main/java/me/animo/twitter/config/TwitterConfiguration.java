package me.animo.twitter.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@Log4j2
@Getter
public class TwitterConfiguration {

    private Configuration config;

    public void loadConfiguration() {
        File file = new File("twitter.json");
        if (file.exists()) {
            try {
                JSONObject jsonObject = new JSONObject(Files.readString(file.toPath(), StandardCharsets.UTF_8));
                parseConfig(jsonObject);
            } catch (JSONException jsonException) {
                log.error("Błąd w odczycie jsona configu", jsonException);
            } catch (IOException ioException) {
                log.error("Błąd w odczycie byteów configu", ioException);
            }
        } else {
            log.warn("Brak pliku konfiguracyjnego... Kopiuję nowy.");
            try {
                InputStream inputStream = getClass().getResourceAsStream("/twitter.json");
                assert inputStream != null;
                Files.copy(inputStream, file.toPath());
                loadConfiguration();
            } catch (Exception exception) {
                log.error("Błąd w powieleniu domyślnego pliku konfiguracyjnego", exception);
            }
        }
    }

    @SneakyThrows
    private void parseConfig(JSONObject jsonObject) {
        ObjectMapper m = new ObjectMapper();
        this.config = m.readValue(jsonObject.toString(), Configuration.class);
    }

    @Data
    public static class Configuration {
        private String chromeDriverPath;
        private String twitterLoginLink;
        private String userAgent;
        private String username;
        private String email;
        private String password;
        private String chatGPTApiLink;
        private String chatGPTApiKey;
        private String chatGPTModel;
        private String chatGPTPrompt;
        private int chatGPTRequestAttempts;
        private String[] chatGPTResponsesToSkip;
        private boolean useImageSender;
        private String imagesFolderPath;
        private boolean imageRandomChooser;
        private boolean useImageDelayStep;
        private int imageSendPostStep;
        private double imageSendPostChance;
        private int replyDelay;
        private int dailyReplyCount;
        private int webDriverWaitTimeout;
        private int webDriverWaitDelay;
        private boolean debug;
        private boolean useProxy;
        private String proxyString;
        private boolean manualLogin;
        private String sessionId;
    }
}
