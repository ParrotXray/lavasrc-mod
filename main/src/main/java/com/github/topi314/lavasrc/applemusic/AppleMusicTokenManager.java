package com.github.topi314.lavasrc.applemusic;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;

public class AppleMusicTokenManager {

	private static final Logger log = LoggerFactory.getLogger(AppleMusicTokenManager.class);
	private static final Pattern TOKEN_PATTERN = Pattern.compile("(?<token>(ey[\\w-]+)\\.([\\w-]+)\\.([\\w-]+))");
	private static final Pattern SCRIPT_TAG_PATTERN = Pattern.compile("<script\\s+type=\"module\"\\s+crossorigin\\s+src=\"([^\"]+)\"");

	private Token token;
	private boolean autoFetch; // 標記是否為自動獲取模式

	public AppleMusicTokenManager(String mediaAPIToken) throws IOException {
		if (mediaAPIToken == null || mediaAPIToken.isEmpty()) {
			this.autoFetch = true;
			this.fetchNewToken();
			log.info("Apple Music token manager initialized in auto-fetch mode");
		} else {
			this.autoFetch = false;
			this.parseTokenData(mediaAPIToken);
			log.info("Apple Music token manager initialized with provided token");
		}
	}

	public Token getToken() throws IOException {
		if (this.token == null) {
			if (this.autoFetch) {
				log.info("Token is null, fetching new token...");
				this.fetchNewToken();
			} else {
				throw new IOException("Apple Music token is not initialized. Please provide a valid token.");
			}
		} else if (this.token.isExpired()) {
			if (this.autoFetch) {
				log.info("Token expired, fetching new token...");
				this.fetchNewToken();
			} else {
				// 手動提供的 token 過期時，不自動刷新，要求用戶更新
				throw new IOException("Apple Music token has expired (expiry: " + this.token.expire + "). Please update your token configuration (mediaAPIToken or MusicKit key).");
			}
		}
		return this.token;
	}

	public void setToken(String mediaAPIToken) throws IOException {
		this.autoFetch = false;
		this.parseTokenData(mediaAPIToken);
		log.info("Token manually updated, auto-fetch disabled");
	}

	private void parseTokenData(String mediaAPIToken) throws IOException {
		if (mediaAPIToken == null || mediaAPIToken.isEmpty()) {
			throw new IllegalArgumentException("Invalid token provided.");
		}

		var parts = mediaAPIToken.split("\\.");
		if (parts.length < 3) {
			throw new IllegalArgumentException("Invalid token provided must have 3 parts separated by '.'");
		}

		try {
			// 處理 Base64URL 編碼（JWT 使用 Base64URL，需要轉換）
			var base64Payload = parts[1]
				.replace('-', '+')
				.replace('_', '/');
			
			// 添加必要的 padding
			int paddingLength = (4 - (base64Payload.length() % 4)) % 4;
			base64Payload += "=".repeat(paddingLength);
			
			var payload = new String(Base64.getDecoder().decode(base64Payload), StandardCharsets.UTF_8);
			var json = JsonBrowser.parse(payload);

			var originNode = json.get("root_https_origin").index(0).text();
			
			// 獲取過期時間
			var expiry = json.get("exp").asLong(0);
			var expireInstant = expiry > 0 ? Instant.ofEpochSecond(expiry) : null;

			this.token = new Token(mediaAPIToken, originNode, expireInstant);
			
			if (expireInstant != null) {
				log.debug("Token parsed successfully, expires at: {}", expireInstant);
			} else {
				log.debug("Token parsed successfully, no expiration time");
			}
		} catch (IllegalArgumentException e) {
			throw new IOException("Failed to decode token payload: " + e.getMessage(), e);
		} catch (Exception e) {
			throw new IOException("Failed to parse token: " + e.getMessage(), e);
		}
	}

	/**
	 * 強制獲取新 token（public 以便在 401 錯誤時調用）
	 */
	public void fetchNewToken() throws IOException {
		log.info("Fetching new Apple Music token from web...");
		
		try (var httpClient = HttpClients.createDefault()) {
			// 1. 獲取主頁 HTML（使用 /us/browse 以獲得更穩定的頁面）
			var mainPageHtml = fetchHtml(httpClient, "https://music.apple.com/us/browse");
			
			// 2. 提取 script 標籤 URL
			var tokenScriptUrl = extractTokenScriptUrl(mainPageHtml);

			if (tokenScriptUrl == null) {
				throw new IllegalStateException("Failed to locate token script URL in Apple Music HTML.");
			}

			log.debug("Token script URL found: {}", tokenScriptUrl);

			// 3. 獲取 JS 文件內容
			var tokenScriptContent = fetchHtml(httpClient, tokenScriptUrl);
			
			// 4. 使用正則提取 token
			var tokenMatcher = TOKEN_PATTERN.matcher(tokenScriptContent);

			if (!tokenMatcher.find()) {
				throw new IllegalStateException("Failed to extract token from script content.");
			}
			
			var extractedToken = tokenMatcher.group("token");
			
			// 檢查是否與舊 token 相同（用於調試）
			if (this.token != null && extractedToken.equals(this.token.apiToken)) {
				log.warn("Fetched token is identical to the old one. Token might be long-lived or fetching method needs update.");
			}
			
			this.parseTokenData(extractedToken);
			log.info("Successfully fetched and parsed new Apple Music token");
		} catch (IOException e) {
			log.error("Failed to fetch new token: {}", e.getMessage());
			throw e;
		}
	}

	private String fetchHtml(CloseableHttpClient httpClient, String url) throws IOException {
		var request = new HttpGet(url);
		// 添加 User-Agent 避免被阻擋
		request.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
		request.addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
		request.addHeader("Accept-Language", "en-US,en;q=0.9");
		
		try (var response = httpClient.execute(request)) {
			var statusCode = response.getStatusLine().getStatusCode();
			if (statusCode != 200) {
				throw new IOException("Failed to fetch URL: " + url + ". Status code: " + statusCode);
			}
			return IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
		}
	}

	private String extractTokenScriptUrl(String html) {
		// 優先使用正則匹配（與 JS 代碼一致，更精確）
		var matcher = SCRIPT_TAG_PATTERN.matcher(html);
		if (matcher.find()) {
			var scriptPath = matcher.group(1);
			var fullUrl = scriptPath.startsWith("http") ? scriptPath : "https://music.apple.com" + scriptPath;
			log.debug("Script URL extracted via regex: {}", fullUrl);
			return fullUrl;
		}

		// Fallback: 使用 Jsoup（兼容性更好）
		log.debug("Regex match failed, trying Jsoup selector...");
		var document = Jsoup.parse(html, "https://music.apple.com");
		
		// 嘗試多個選擇器
		var selectors = new String[]{
			"script[type=module][crossorigin][src]",
			"script[type=module][src~=/assets/index.*.js]",
			"script[type=module][src*=index][src*=.js]"
		};
		
		for (var selector : selectors) {
			var result = document.select(selector)
				.stream()
				.findFirst()
				.map(element -> {
					var src = element.attr("src");
					return src.startsWith("http") ? src : "https://music.apple.com" + src;
				})
				.orElse(null);
			
			if (result != null) {
				log.debug("Script URL extracted via Jsoup ({}): {}", selector, result);
				return result;
			}
		}
		
		log.error("Failed to extract token script URL from HTML");
		return null;
	}

	/**
	 * 檢查當前是否為自動獲取模式
	 */
	public boolean isAutoFetch() {
		return this.autoFetch;
	}

	public static class Token {
		public final String apiToken;
		public final String origin;
		public final Instant expire;

		public Token(String apiToken, String origin, Instant expire) {
			this.apiToken = apiToken;
			this.origin = origin;
			this.expire = expire;
		}

		/**
		 * 檢查 token 是否過期
		 * - 提前 10 秒判斷過期（與 JS 代碼的 10000ms 一致）
		 * - 沒有過期時間時認為長期有效
		 */
		public boolean isExpired() {
			if (this.apiToken == null) {
				return true;
			}
			if (this.expire == null) {
				// 沒有過期時間，認為 token 長期有效（與 JS 代碼 _isTokenValid 邏輯一致）
				return false;
			}
			// 提前 10 秒判斷過期（與 JS 代碼的 10000ms 一致）
			return expire.minusSeconds(10).isBefore(Instant.now());
		}
	}
}