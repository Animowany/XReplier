package me.animo.twitter;

import com.google.common.io.Files;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import me.animo.twitter.config.TwitterConfiguration;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.json.JSONObject;
import org.openqa.selenium.*;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Log4j2
public class TwitterReplier {
    final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(4);
    TwitterConfiguration.Configuration configuration;

    final Set<Long> repliedTweets = new HashSet<>();

    public static void main(String[] args) {
        new TwitterReplier().start();
    }

    public TwitterReplier() {
        TwitterConfiguration twitterConfiguration = new TwitterConfiguration();
        twitterConfiguration.loadConfiguration();
        configuration = twitterConfiguration.getConfig();
        Configurator.setRootLevel(configuration.isDebug() ? Level.DEBUG : Level.INFO);
    }

    public int imageIndex = 0;
    public List<String> lastImagesSent = new ArrayList<>();

    @SneakyThrows
    public void start() {
        log.debug("Installing driver");
        System.setProperty("webdriver.chrome.driver", configuration.getChromeDriverPath());

        log.debug("Starting driver, and setup chromeoptions (useragent)");
        ChromeOptions chromeOptions = new ChromeOptions();
        chromeOptions.addArguments(String.format("--user-agent=%s", configuration.getUserAgent()));
        chromeOptions.addArguments("--no-sandbox");
        chromeOptions.addArguments("--disable-search-engine-choice-screen");
        chromeOptions.addArguments("--headless");
        chromeOptions.addArguments("--window-size=1920,1080");
        if (configuration.isUseProxy()) {
            chromeOptions.addArguments(String.format("--proxy-server=%s", configuration.getProxyString()));
        }
        final ChromeDriver driver = new ChromeDriver(chromeOptions);
        log.debug("Maximize window");
        driver.manage().window().maximize();

        executorService.scheduleAtFixedRate(() -> {
            log.debug("Open page");
            driver.get(configuration.getTwitterLoginLink());

            WebDriverWait webDriverWait = new WebDriverWait(driver, Duration.ofSeconds(configuration.getWebDriverWaitTimeout()), Duration.ofSeconds(configuration.getWebDriverWaitDelay()));

            log.info("Logging");
            if (configuration.isManualLogin()) {
                WebElement usernameInput = webDriverWait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("input[autocomplete=\"username\"]")));
                usernameInput.sendKeys(configuration.getEmail());
                WebElement nextButton = webDriverWait.until(ExpectedConditions.elementToBeClickable(By.cssSelector("button[data-testid=\"apple_sign_in_button\"] + div + div + button")));
                clickElement(driver, nextButton);
                try { //check od weryfki nr 1
                    log.info("Filling twitter suspicious login prompt");
                    WebElement authorizeWithNickname = webDriverWait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("input[data-testid=\"ocfEnterTextTextInput\"]")));
                    authorizeWithNickname.sendKeys(configuration.getUsername());
                    WebElement authorizeNext = webDriverWait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("button[data-testid=\"ocfEnterTextNextButton\"]")));
                    clickElement(driver, authorizeNext);
                } catch (Exception ignored) {
                }
                WebElement passwordInput = webDriverWait.until(ExpectedConditions.visibilityOfElementLocated(By.name("password")));
                passwordInput.sendKeys(configuration.getPassword());
                WebElement loginButton = webDriverWait.until(ExpectedConditions.elementToBeClickable(By.cssSelector("button[data-testid=\"LoginForm_Login_Button\"]")));
                clickElement(driver, loginButton);

                try { //check od weryfki nr 2
                    WebElement emailCode = webDriverWait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("input[data-testid=\"ocfEnterTextTextInput\"]")));
                    Scanner scanner = new Scanner(System.in);
                    log.info("Login restricted, mfa code needed:\n");
                    emailCode.sendKeys(scanner.nextLine());
                    scanner.close();
                    WebElement authorizeNext = webDriverWait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("button[data-testid=\"ocfEnterTextNextButton\"]")));
                    clickElement(driver, authorizeNext);
                } catch (Exception ignored) {
                }
            } else {
                log.info("Injecting cookies");
                driver.manage().addCookie(new Cookie("auth_token", configuration.getSessionId(), ".x.com", "/", null));
                driver.get("https://x.com");
            }
            log.info("Logged in");

            log.debug("Searching tweets");
            WebElement forYouTweetBlock = webDriverWait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("div[aria-label=\"Home timeline\"]")));
            WebElement forYouTweets = webDriverWait.until(ExpectedConditions.presenceOfNestedElementLocatedBy(forYouTweetBlock, By.cssSelector("section[aria-labelledby^=\"accessible-list\"] > div > div")));

            for (int i = 1; i <= configuration.getDailyReplyCount();) {
                List<WebElement> loadedTweets = forYouTweets.findElements(By.cssSelector("div[data-testid=\"cellInnerDiv\"]"));
                WebElement lastElement = loadedTweets.get(loadedTweets.size() - 1);
                for (WebElement loadedTweet : loadedTweets) {
                    try {
                        log.debug("Checking tweet author");

                        WebElement userNameBlock = webDriverWait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("div[data-testid=\"User-Name\"]")));
                        WebElement userNameHref = webDriverWait.until(ExpectedConditions.presenceOfNestedElementLocatedBy(userNameBlock, By.cssSelector("a[role=\"link\"]")));

                        if (userNameHref.getAttribute("href").replaceFirst("https://x.com/", "").equalsIgnoreCase(configuration.getUsername())) {
                            log.debug("Author dump: {}", userNameHref.getAttribute("href").replaceFirst("https://x.com/", ""));
                            continue;
                        }

                        log.debug("Checking if already replied to this tweet");

                        WebElement tweetIdBlock = webDriverWait.until(ExpectedConditions.presenceOfNestedElementLocatedBy(loadedTweet, By.cssSelector("a[href*=\"status\"]")));
                        long tweetId = Arrays.stream(tweetIdBlock.getAttribute("href").replaceFirst("https://x.com/", "").split("/")).filter(this::isLong).map(Long::parseLong).findFirst().get();
                        if (repliedTweets.contains(tweetId)) {
                            log.debug("Already replied to this tweet.");
                            continue;
                        }

                        log.debug("Checking tweet post date");
                        try {
                            WebElement tweetTime = webDriverWait.until(ExpectedConditions.presenceOfNestedElementLocatedBy(loadedTweet, By.cssSelector("time")));
                            tweetTime.getAttribute("datetime");
                            Instant instant = Instant.parse(tweetTime.getAttribute("datetime"));

                            Instant currentDate = Instant.now();

                            Duration duration = Duration.between(currentDate, instant);

                            if (duration.toHours() > 0 && duration.toHours() > 24) {
                                continue;
                            }
                        } catch (Exception exception) {
                            log.debug("Exception during tweet post date check:\n{}", exception.getLocalizedMessage());
                            continue;
                        }

                        log.debug("Found valid tweet");
                        try {
                            WebElement replyButton = webDriverWait.until(ExpectedConditions.presenceOfNestedElementLocatedBy(loadedTweet, By.cssSelector("button[data-testid=\"reply\"]")));
                            WebElement replyButtonClickable = webDriverWait.until(ExpectedConditions.elementToBeClickable(replyButton));
                            clickElement(driver, replyButtonClickable);
                        } catch (Exception exception) {
                            log.debug("No reply button:\n{}", exception.getLocalizedMessage());
                            continue;
                        }

                        log.debug("Waiting for modal");
                        WebElement modalElement = webDriverWait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("div[aria-labelledby=\"modal-header\"]")));
                        if (modalElement != null) {
                            log.debug("Modal found");
                            WebElement tweetText = webDriverWait.until(ExpectedConditions.presenceOfNestedElementLocatedBy(modalElement, By.cssSelector("div[data-testid=\"tweetText\"]")));
                            StringBuilder tweetTextBuilder = new StringBuilder();
                            List<WebElement> textSpans = tweetText.findElements(By.cssSelector("span"));
                            log.debug("Building text from spans: {} spans", textSpans.size());
                            for (WebElement textSpan : textSpans) {
                                try {
                                    if (!textSpan.getText().contains("https") || !textSpan.getText().contains("pic.x.com")) {
                                        tweetTextBuilder.append(textSpan.getText().replace("https://", ""));
                                    }
                                } catch (NoSuchElementException ignored) {}
                            }
                            String tweetBuiltText = tweetTextBuilder.toString();
                            log.debug("Built text");
                            try {
                                if (tweetBuiltText.isEmpty()) {
                                    log.debug("Text is empty");
                                    throw new Exception("Text is empty");
                                }
                                log.info(tweetBuiltText);
                                WebElement tweetTextBox = webDriverWait.until(ExpectedConditions.presenceOfNestedElementLocatedBy(modalElement, By.cssSelector("div[aria-label=\"Post text\"]")));
                                WebElement tweetInput = webDriverWait.until(ExpectedConditions.presenceOfNestedElementLocatedBy(modalElement, By.cssSelector("input[data-testid=\"fileInput\"]")));
                                for (int k = 0; k < configuration.getChatGPTRequestAttempts(); k++) {
                                    log.debug("Generating chatgpt response");
                                    try {
                                        String chatGPTResponse = chatGPT(configuration.getChatGPTPrompt().replace("{TWEET_MESSAGE}", tweetBuiltText));
                                        tweetTextBox.sendKeys(chatGPTResponse);
                                        if (configuration.isUseImageSender()) {
                                            try {
                                                if (configuration.isUseImageDelayStep() && repliedTweets.size() % configuration.getImageSendPostStep() != 0) {
                                                    break;
                                                } else if (!configuration.isUseImageDelayStep() && !getChance(configuration.getImageSendPostChance())) {
                                                    break;
                                                }
                                                log.debug("Attempting to send image");
                                                String imagePath;
                                                List<File> imagesList = Arrays.asList(Objects.requireNonNull(new File(configuration.getImagesFolderPath()).listFiles()));
                                                if (imagesList.isEmpty()) {
                                                    log.debug("Images sending is enabled, but folder is empty.");
                                                }
                                                if (configuration.isImageRandomChooser()) {
                                                    Collections.shuffle(imagesList);
                                                    imagePath = imagesList.stream().map(File::getAbsolutePath).filter(path -> !lastImagesSent.contains(path)).findAny().get();
                                                    lastImagesSent.add(imagePath);
                                                    if (lastImagesSent.size() > imagesList.size() / 10) {
                                                        lastImagesSent.remove(0);
                                                    }
                                                } else {
                                                    if (imageIndex >= imagesList.size()) imageIndex = 0;
                                                    imagePath = imagesList.get(imageIndex++).getAbsolutePath();
                                                }
                                                tweetInput.sendKeys(imagePath);
                                                webDriverWait.until(ExpectedConditions.presenceOfNestedElementLocatedBy(modalElement, By.cssSelector("button[aria-label=\"Remove media\"]")));
                                            } catch (Exception exception) {
                                                log.error("Error during image sending process: {}", exception.getLocalizedMessage());
                                            }
                                        }
                                        break;
                                    } catch (Exception ignored) {
                                        log.debug("Retrying chatgpt response");
                                        if (k == configuration.getChatGPTRequestAttempts() - 1) {
                                            throw new Exception("Chatgpt couldnt provide a response");
                                        }
                                    }
                                }
                                WebElement tweetSendButton = webDriverWait.until(ExpectedConditions.presenceOfNestedElementLocatedBy(modalElement, By.cssSelector("button[data-testid=\"tweetButton\"]")));
                                WebElement tweetSendButtonClickable = webDriverWait.until(ExpectedConditions.elementToBeClickable(tweetSendButton));
                                log.debug("Sending tweet");
                                clickElement(driver, tweetSendButtonClickable);
                                log.debug("Adding tweet to replied list");
                                repliedTweets.add(tweetId);
                                log.debug("Begin reply delay {} seconds", configuration.getReplyDelay());
                                Thread.sleep(configuration.getReplyDelay() * 1000L);
                                if (tweetSendButtonClickable.isDisplayed()) {
                                    try {
                                        WebElement closeTweetButton = webDriverWait.until(ExpectedConditions.presenceOfNestedElementLocatedBy(modalElement, By.cssSelector("button[data-testid=\"app-bar-close\"]")));
                                        WebElement closeTweetButtonClickable = webDriverWait.until(ExpectedConditions.elementToBeClickable(closeTweetButton));
                                        clickElement(driver, closeTweetButtonClickable);
                                        WebElement cancelTweetButton = webDriverWait.until(ExpectedConditions.presenceOfNestedElementLocatedBy(modalElement, By.cssSelector("button[data-testid=\"confirmationSheetCancel\"]")));
                                        WebElement cancelTweetButtonClickable = webDriverWait.until(ExpectedConditions.elementToBeClickable(cancelTweetButton));
                                        clickElement(driver, cancelTweetButtonClickable);
                                    } catch (Exception ignored1) {}
                                    Thread.sleep(3000L);
                                }
                                i++;
                                if (i > configuration.getDailyReplyCount()) {
                                    log.info("Daily reply limit exceed");
                                    return;
                                }
                            } catch (Exception exception) {
                                log.debug("Error in post creating process, closing it: {}", exception.getLocalizedMessage(), exception);
                                try {
                                    WebElement closeTweetButton = webDriverWait.until(ExpectedConditions.presenceOfNestedElementLocatedBy(modalElement, By.cssSelector("button[data-testid=\"app-bar-close\"]")));
                                    WebElement closeTweetButtonClickable = webDriverWait.until(ExpectedConditions.elementToBeClickable(closeTweetButton));
                                    clickElement(driver, closeTweetButtonClickable);
                                    WebElement cancelTweetButton = webDriverWait.until(ExpectedConditions.presenceOfNestedElementLocatedBy(modalElement, By.cssSelector("button[data-testid=\"confirmationSheetCancel\"]")));
                                    WebElement cancelTweetButtonClickable = webDriverWait.until(ExpectedConditions.elementToBeClickable(cancelTweetButton));
                                    clickElement(driver, cancelTweetButtonClickable);
                                } catch (Exception ignored1) {}
                            }
                        }
                    } catch (Exception exception) {
                        log.error(exception.getLocalizedMessage(), exception);
                        //makeScreenshot(driver);
                        try {
                            WebElement sheetDialog = webDriverWait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("div[data-testid=\"sheetDialog\"]")));
                            WebElement sheetDialogClose = webDriverWait.until(ExpectedConditions.presenceOfNestedElementLocatedBy(sheetDialog, By.cssSelector("button[aria-label=\"Close\"]")));
                            WebElement sheetDialogCloseClickable = webDriverWait.until(ExpectedConditions.elementToBeClickable(sheetDialogClose));
                            log.debug("Closing popup");
                            clickElement(driver, sheetDialogCloseClickable);
                        } catch (Exception ignored) {}
                    }
                }

                while (true) {
                    try {
                        loadedTweets = forYouTweets.findElements(By.cssSelector("div[data-testid=\"cellInnerDiv\"]"));
                        if (!loadedTweets.contains(lastElement)) {
                            lastElement = loadedTweets.get(loadedTweets.size() - 1);
                            break;
                        } else {
                            driver.executeScript("window.scrollBy(0, 1000)");
                        }
                    } catch (Exception e) {
                        log.error(e.getLocalizedMessage());
                        if (e.getLocalizedMessage().contains("invalid session id") || e.getLocalizedMessage().contains("stale element not found")) {
                            break;
                        }
                    }
                }
            }
            driver.quit();
        },0L, 1L, TimeUnit.DAYS);
    }

    public String chatGPT(String prompt) throws Exception {
        String url = configuration.getChatGPTApiLink();
        String apiKey = configuration.getChatGPTApiKey();
        String model = configuration.getChatGPTModel();

        try {
            URL obj = new URL(url);
            HttpURLConnection connection = (HttpURLConnection) obj.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Content-Type", "application/json");

            String body = "{\"model\": \"" + model + "\", \"messages\": [{\"role\": \"user\", \"content\": \"" + prompt + "\"}]}";
            connection.setDoOutput(true);
            OutputStreamWriter writer = new OutputStreamWriter(connection.getOutputStream());
            writer.write(body);
            writer.flush();
            writer.close();

            BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            String line;

            StringBuilder response = new StringBuilder();

            while ((line = br.readLine()) != null) {
                response.append(line);
            }
            br.close();

            JSONObject jsonObject = new JSONObject(response.toString());
            String chat_response = jsonObject.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").replaceAll("[^\\u0000-\\uFFFF]", "");
            log.info(chat_response);
            if (Arrays.stream(configuration.getChatGPTResponsesToSkip()).anyMatch(blockedWord -> chat_response.toLowerCase().contains(blockedWord.toLowerCase()))) {
                throw new IOException("Blocked words provided");
            }
            if (chat_response.isEmpty()) throw new IOException("No response provided");
            return chat_response;
        } catch (IOException e) {
            log.error("Error during chatgpt api request");
            throw new Exception(e);
        }
    }

    private void clickElement(ChromeDriver chromeDriver, WebElement webElement) {
        chromeDriver.executeScript("arguments[0].click();", webElement);
    }


    private void makeScreenshot(ChromeDriver driver) {
        File screenshot = driver.getScreenshotAs(OutputType.FILE);
        String screenshotPath = "/home/Twitter/"+System.currentTimeMillis()+".png";
        try {
            Files.copy(screenshot, new File(screenshotPath));
        } catch (IOException e) {
            log.error(e.getLocalizedMessage(), e);
        }
    }

    public boolean isLong(String longString) {
        try {
            Long.parseLong(longString);
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    public boolean getChance(double chance) {
        if (chance < 0 || chance > 1) {
            throw new IllegalArgumentException("Chance must be between 0 and 1.");
        }

        double randomValue = Math.random();

        return randomValue < chance;
    }
}
