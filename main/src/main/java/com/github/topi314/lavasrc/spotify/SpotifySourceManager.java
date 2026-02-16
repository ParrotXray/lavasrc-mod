package com.github.topi314.lavasrc.spotify;

import com.github.topi314.lavalyrics.AudioLyricsManager;
import com.github.topi314.lavalyrics.lyrics.AudioLyrics;
import com.github.topi314.lavalyrics.lyrics.BasicAudioLyrics;
import com.github.topi314.lavasearch.AudioSearchManager;
import com.github.topi314.lavasearch.result.AudioSearchResult;
import com.github.topi314.lavasearch.result.BasicAudioSearchResult;
import com.github.topi314.lavasrc.ExtendedAudioPlaylist;
import com.github.topi314.lavasrc.LavaSrcTools;
import com.github.topi314.lavasrc.mirror.DefaultMirroringAudioTrackResolver;
import com.github.topi314.lavasrc.mirror.MirroringAudioSourceManager;
import com.github.topi314.lavasrc.mirror.MirroringAudioTrackResolver;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.*;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.apache.http.impl.client.HttpClientBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.io.DataInput;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class SpotifySourceManager extends MirroringAudioSourceManager implements HttpConfigurable, AudioSearchManager, AudioLyricsManager {

	public static final Pattern URL_PATTERN = Pattern.compile("(https?://)(www\\.)?open\\.spotify\\.com/((?<region>[a-zA-Z-]+)/)?(user/(?<user>[a-zA-Z0-9-_]+)/)?(?<type>track|album|playlist|artist)/(?<identifier>[a-zA-Z0-9-_]+)");
	public static final Pattern RADIO_MIX_QUERY_PATTERN = Pattern.compile("mix:(?<seedType>album|artist|track|isrc):(?<seed>[a-zA-Z0-9-_]+)");
	public static final String SEARCH_PREFIX = "spsearch:";
	public static final String RECOMMENDATIONS_PREFIX = "sprec:";
	public static final String PREVIEW_PREFIX = "spprev:";
	public static final long PREVIEW_LENGTH = 30000;
	public static final String SHARE_URL = "https://spotify.link/";
	public static final int PLAYLIST_MAX_PAGE_ITEMS = 100;
	public static final int ALBUM_MAX_PAGE_ITEMS = 50;

	public static final String PARTNER_API_BASE = "https://api-partner.spotify.com/pathfinder/v2/query";
	public static final String WEB_API_BASE = "https://api.spotify.com/v1/";
	public static final String CLIENT_API_BASE = "https://spclient.wg.spotify.com/";

	private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.6998.178 Spotify/1.2.65.255 Safari/537.36";
	private static final String APP_PLATFORM = "WebPlayer";
	private static final String SPOTIFY_APP_VERSION = "1.2.81.104.g225ec0e6";
	private static final String BASE62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

	public static final Set<AudioSearchResult.Type> SEARCH_TYPES = Set.of(AudioSearchResult.Type.ALBUM, AudioSearchResult.Type.ARTIST, AudioSearchResult.Type.PLAYLIST, AudioSearchResult.Type.TRACK);
	private static final Logger log = LoggerFactory.getLogger(SpotifySourceManager.class);

	private static final Map<String, PersistedQuery> PERSISTED_QUERIES = Map.ofEntries(
		Map.entry("getTrack", new PersistedQuery(
			"getTrack",
			"612585ae06ba435ad26369870deaae23b5c8800a256cd8a57e08eddc25a37294"
		)),
		Map.entry("getAlbum", new PersistedQuery(
			"getAlbum",
			"b9bfabef66ed756e5e13f68a942deb60bd4125ec1f1be8cc42769dc0259b4b10"
		)),
		Map.entry("getPlaylist", new PersistedQuery(
			"fetchPlaylist",
			"bb67e0af06e8d6f52b531f97468ee4acd44cd0f82b988e15c2ea47b1148efc77"
		)),
		Map.entry("getArtist", new PersistedQuery(
			"queryArtistOverview",
			"35648a112beb1794e39ab931365f6ae4a8d45e65396d641eeda94e4003d41497"
		)),
		Map.entry("getRecommendations", new PersistedQuery(
			"internalLinkRecommenderTrack",
			"c77098ee9d6ee8ad3eb844938722db60570d040b49f41f5ec6e7be9160a7c86b"
		)),
		Map.entry("searchDesktop", new PersistedQuery(
			"searchDesktop",
			"fcad5a3e0d5af727fb76966f06971c19cfa2275e6ff7671196753e008611873c"
		))
	);

	private static class PersistedQuery {
		final String operationName;
		final String sha256Hash;

		PersistedQuery(String operationName, String sha256Hash) {
			this.operationName = operationName;
			this.sha256Hash = sha256Hash;
		}
	}

	private final HttpInterfaceManager httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager();
	private final SpotifyTokenTracker tokenTracker;
	private final String countryCode;
	private int playlistPageLimit = 6;
	private int albumPageLimit = 6;
	private boolean localFiles;
	private boolean resolveArtistsInSearch = true;
	private boolean preferAnonymousToken = false;

	public static String base62ToHex(String input) {
		BigInteger result = BigInteger.ZERO;
		for (char c : input.toCharArray()) {
			int index = BASE62.indexOf(c);
			result = result.multiply(BigInteger.valueOf(62)).add(BigInteger.valueOf(index));
		}
		return String.format("%032x", result);
	}

	private JsonBrowser fetchTrackMetadata(String id) throws IOException {
		var hexId = base62ToHex(id);
		var accessToken = this.tokenTracker.getAnonymousAccessToken();
		
		var request = new HttpGet(CLIENT_API_BASE + "metadata/4/track/" + hexId + "?market=from_token");
		request.setHeader("Authorization", "Bearer " + accessToken);
		request.setHeader("User-Agent", USER_AGENT);
		request.setHeader("App-Platform", APP_PLATFORM);
		request.setHeader("Spotify-App-Version", SPOTIFY_APP_VERSION);
		request.setHeader("Accept", "application/json");
		
		try (var response = this.httpInterfaceManager.getInterface().execute(request)) {
			var statusCode = response.getStatusLine().getStatusCode();
			if (statusCode != 200) {
				return null;
			}
			
			var entity = response.getEntity();
			if (entity == null) {
				return null;
			}
			
			var bodyStr = EntityUtils.toString(entity, StandardCharsets.UTF_8);
			return JsonBrowser.parse(bodyStr);
		} catch (IOException e) {
			log.debug("Failed to fetch metadata for track {}: {}", id, e.getMessage());
			return null;
		}
	}

	public SpotifySourceManager(String[] providers, String clientId, String clientSecret, String countryCode, AudioPlayerManager audioPlayerManager) {
		this(clientId, clientSecret, null, countryCode, unused -> audioPlayerManager, new DefaultMirroringAudioTrackResolver(providers));
	}

	public SpotifySourceManager(String[] providers, String clientId, String clientSecret, String countryCode, Function<Void, AudioPlayerManager> audioPlayerManager) {
		this(clientId, clientSecret, null, countryCode, audioPlayerManager, new DefaultMirroringAudioTrackResolver(providers));
	}

	public SpotifySourceManager(String clientId, String clientSecret, String countryCode, AudioPlayerManager audioPlayerManager, MirroringAudioTrackResolver mirroringAudioTrackResolver) {
		this(clientId, clientSecret, null, countryCode, unused -> audioPlayerManager, mirroringAudioTrackResolver);
	}

	public SpotifySourceManager(String clientId, String clientSecret, String countryCode, Function<Void, AudioPlayerManager> audioPlayerManager, MirroringAudioTrackResolver mirroringAudioTrackResolver) {
		this(clientId, clientSecret, null, countryCode, audioPlayerManager, mirroringAudioTrackResolver);
	}

	public SpotifySourceManager(String clientId, String clientSecret, String spDc, String countryCode, Function<Void, AudioPlayerManager> audioPlayerManager, MirroringAudioTrackResolver mirroringAudioTrackResolver) {
		this(clientId, clientSecret, false , spDc, countryCode, audioPlayerManager, mirroringAudioTrackResolver);
	}

	public SpotifySourceManager(String clientId, String clientSecret, boolean preferAnonymousToken, String spDc, String countryCode, Function<Void, AudioPlayerManager> audioPlayerManager, MirroringAudioTrackResolver mirroringAudioTrackResolver) {
		this(clientId, clientSecret, preferAnonymousToken, null, spDc, countryCode, audioPlayerManager, mirroringAudioTrackResolver);
	}

	public SpotifySourceManager(String clientId, String clientSecret, boolean preferAnonymousToken, String customTokenEndpoint, String spDc, String countryCode, Function<Void, AudioPlayerManager> audioPlayerManager, MirroringAudioTrackResolver mirroringAudioTrackResolver) {
		super(audioPlayerManager, mirroringAudioTrackResolver);

		this.tokenTracker = new SpotifyTokenTracker(this, clientId, clientSecret, spDc, customTokenEndpoint);

		if (countryCode == null || countryCode.isEmpty()) {
			countryCode = "US";
		}
		this.countryCode = countryCode;
		this.preferAnonymousToken = preferAnonymousToken;

		if (shouldUsePartnerAPI()) {
			log.info("Spotify source manager initialized with Partner API");
		} else {
			log.info("Spotify source manager initialized with Web API");
		}
	}

	public void setPlaylistPageLimit(int playlistPageLimit) {
		this.playlistPageLimit = playlistPageLimit;
	}

	public void setAlbumPageLimit(int albumPageLimit) {
		this.albumPageLimit = albumPageLimit;
	}

	public void setLocalFiles(boolean localFiles) {
		this.localFiles = localFiles;
	}

	public void setResolveArtistsInSearch(boolean resolveArtistsInSearch) {
		this.resolveArtistsInSearch = resolveArtistsInSearch;
	}

	public void setClientIDSecret(String clientId, String clientSecret) {
		this.tokenTracker.setClientIDS(clientId, clientSecret);
	}

	public void setSpDc(String spDc) {
		this.tokenTracker.setSpDc(spDc);
	}

	public void setPreferAnonymousToken(boolean preferAnonymousToken) {
		this.preferAnonymousToken = preferAnonymousToken;
	}

	public void setCustomTokenEndpoint(String customTokenEndpoint) {
		this.tokenTracker.setCustomTokenEndpoint(customTokenEndpoint);
	}

	@NotNull
	@Override
	public String getSourceName() {
		return "spotify";
	}

	private boolean shouldUsePartnerAPI() {
		if (tokenTracker.hasCustomTokenEndpoint()) {
			return true;
		}

		if (!tokenTracker.hasValidCredentials() && tokenTracker.hasValidAccountCredentials()) {
			return true;
		}

		if (preferAnonymousToken) {
			return true;
		}

		return false;
	}

	private JsonBrowser queryPartnerAPI(String queryKey, JsonBrowser variables) throws IOException {
		var query = PERSISTED_QUERIES.get(queryKey);
		if (query == null) {
			throw new IllegalArgumentException("Unknown Partner API query: " + queryKey);
		}

		var accessToken = this.tokenTracker.getAnonymousAccessToken();

		var requestBody = JsonBrowser.newMap();
		requestBody.put("operationName", query.operationName);
		requestBody.put("variables", variables);

		var extensions = JsonBrowser.newMap();
		var persistedQuery = JsonBrowser.newMap();
		persistedQuery.put("version", 1);
		persistedQuery.put("sha256Hash", query.sha256Hash);
		extensions.put("persistedQuery", persistedQuery);
		requestBody.put("extensions", extensions);

		var request = new HttpPost(PARTNER_API_BASE);
		request.setHeader("Authorization", "Bearer " + accessToken);
		request.setHeader("User-Agent", USER_AGENT);
		request.setHeader("App-Platform", APP_PLATFORM);
		request.setHeader("Spotify-App-Version", SPOTIFY_APP_VERSION);
		request.setHeader("Content-Type", "application/json; charset=utf-8");
		request.setEntity(new StringEntity(requestBody.format(), StandardCharsets.UTF_8));

		var response = LavaSrcTools.fetchResponseAsJson(this.httpInterfaceManager.getInterface(), request);

		if (response == null || !response.get("errors").isNull()) {
			if (response != null) {
				var errors = response.get("errors");
				log.error("Partner API error for {}: {}", query.operationName, errors.format());
			}
			return null;
		}

		return response;
	}

	private AudioTrack parsePartnerTrack(JsonBrowser json, boolean preview) {
		var name = json.get("name").text();
		var uri = json.get("uri").text();
		var id = uri.split(":")[2]; // spotify:track:{id}

		String isrc = null;
		try {
			var metadata = fetchTrackMetadata(id);
			if (metadata != null) {
				
				var externalIds = metadata.get("external_id");
				if (!externalIds.isNull() && externalIds.isList()) {
					for (var extId : externalIds.values()) {
						if ("isrc".equals(extId.get("type").text())) {
							isrc = extId.get("id").text();
							break;
						}
					}
				} else {
					log.warn("No external_id found in metadata for track {}", id);
				}
			} else {
				log.warn("Metadata is null for track {}", id);
			}
		} catch (IOException e) {
			log.warn("Failed to fetch metadata for track {}, falling back to basic info", id, e);
		}

		var artists = json.get("artists").get("items");
		var artistName = "Unknown";
		if (!artists.values().isEmpty()) {
			artistName = artists.values().stream()
				.map(artist -> artist.get("profile").get("name").text())
				.collect(Collectors.joining(", "));
		}

		var albumData = json.get("albumOfTrack");
		var albumName = albumData.get("name").text();
		var albumUrl = albumData.get("uri").text()
			.replace("spotify:album:", "https://open.spotify.com/album/");

		var coverArt = albumData.get("coverArt").get("sources");
		String artworkUrl = null;
		if (!coverArt.values().isEmpty()) {
			artworkUrl = coverArt.index(0).get("url").text();
		}

		var duration = json.get("duration").isNull() 
			? json.get("trackDuration").get("totalMilliseconds").asLong(0) 
			: json.get("duration").get("totalMilliseconds").asLong(0);

		String artistUrl = null;
		String artistArtworkUrl = null;
		if (!artists.values().isEmpty()) {
			var firstArtist = artists.index(0);
			var artistUri = firstArtist.get("uri").text();
			artistUrl = artistUri.replace("spotify:artist:", "https://open.spotify.com/artist/");

			var artistVisuals = firstArtist.get("visuals").get("avatarImage");
			if (!artistVisuals.isNull() && !artistVisuals.get("sources").values().isEmpty()) {
				artistArtworkUrl = artistVisuals.get("sources").index(0).get("url").text();
			}
		}

		String previewUrl = null;
		var canvas = json.get("canvas");
		if (!canvas.isNull() && !canvas.get("url").isNull()) {
			previewUrl = canvas.get("url").text();
		} else {
			var audioPreview = json.get("audioPreview");
			if (!audioPreview.isNull() && !audioPreview.get("url").isNull()) {
				previewUrl = audioPreview.get("url").text();
			}
		}

		var trackUrl = uri.replace("spotify:track:", "https://open.spotify.com/track/");

		return new SpotifyAudioTrack(
			new AudioTrackInfo(
				name,
				artistName,
				preview ? PREVIEW_LENGTH : duration,
				id,
				false,
				trackUrl,
				artworkUrl,
				isrc
			),
			albumName,
			albumUrl,
			artistUrl,
			artistArtworkUrl,
			previewUrl,
			preview,
			this
		);
	}

	private AudioItem parsePartnerAlbum(JsonBrowser albumData, boolean preview) {
		var albumName = albumData.get("name").text();
		var albumUrl = albumData.get("uri").text()
			.replace("spotify:album:", "https://open.spotify.com/album/");

		var coverArt = albumData.get("coverArt").get("sources");
		String artworkUrl = null;
		if (!coverArt.values().isEmpty()) {
			artworkUrl = coverArt.index(0).get("url").text();
		}

		var artists = albumData.get("artists").get("items");
		String artistName = null;
		if (!artists.values().isEmpty()) {
			artistName = artists.index(0).get("profile").get("name").text();
		}

		var tracks = new ArrayList<AudioTrack>();
		var tracksData = albumData.get("tracks").get("items");
		var totalCount = albumData.get("tracks").get("totalCount").asLong(0);

		for (var trackItem : tracksData.values()) {
			var track = trackItem.get("track");
			if (!track.isNull()) {
				tracks.add(parsePartnerTrack(track, preview));
			}
		}

		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		return new SpotifyAudioPlaylist(
			albumName,
			tracks,
			ExtendedAudioPlaylist.Type.ALBUM,
			albumUrl,
			artworkUrl,
			artistName,
			(int) totalCount
		);
	}

	private AudioItem parsePartnerPlaylist(JsonBrowser playlistData, boolean preview) {
		var playlistName = playlistData.get("name").text();
		var playlistUrl = playlistData.get("uri").text()
			.replace("spotify:playlist:", "https://open.spotify.com/playlist/");

		var images = playlistData.get("images").get("items");
		String artworkUrl = null;
		if (!images.values().isEmpty()) {
			var sources = images.index(0).get("sources");
			if (!sources.values().isEmpty()) {
				artworkUrl = sources.index(0).get("url").text();
			}
		}

		var owner = playlistData.get("ownerV2").get("data");
		String ownerName = null;
		if (!owner.isNull()) {
			ownerName = owner.get("name").text();
		}

		var tracks = new ArrayList<AudioTrack>();
		var items = playlistData.get("content").get("items");
		var totalCount = playlistData.get("content").get("totalCount").asLong(0);

		for (var item : items.values()) {
			var itemData = item.get("itemV2").get("data");

			var typename = itemData.get("__typename").text();
			if ("Episode".equals(typename) || "LocalTrack".equals(typename)) {
				continue;
			}

			if (!this.localFiles && "LocalTrack".equals(typename)) {
				continue;
			}

			if (!itemData.isNull()) {
				tracks.add(parsePartnerTrack(itemData, preview));
			}
		}

		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		return new SpotifyAudioPlaylist(
			playlistName,
			tracks,
			ExtendedAudioPlaylist.Type.PLAYLIST,
			playlistUrl,
			artworkUrl,
			ownerName,
			(int) totalCount
		);
	}

	@Override
	@Nullable
	public AudioLyrics loadLyrics(@NotNull AudioTrack audioTrack) {
		var spotifyTackId = "";
		if (audioTrack instanceof SpotifyAudioTrack) {
			spotifyTackId = audioTrack.getIdentifier();
		}

		if (spotifyTackId.isEmpty()) {
			AudioItem item = AudioReference.NO_TRACK;
			try {
				if (audioTrack.getInfo().isrc != null && !audioTrack.getInfo().isrc.isEmpty()) {
					item = this.getSearch("isrc:" + audioTrack.getInfo().isrc, false);
				}
				if (item == AudioReference.NO_TRACK) {
					item = this.getSearch(String.format("%s %s", audioTrack.getInfo().title, audioTrack.getInfo().author), false);
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}

			if (item == AudioReference.NO_TRACK) {
				return null;
			}
			if (item instanceof AudioTrack) {
				spotifyTackId = ((AudioTrack) item).getIdentifier();
			} else if (item instanceof AudioPlaylist) {
				var playlist = (AudioPlaylist) item;
				if (!playlist.getTracks().isEmpty()) {
					spotifyTackId = playlist.getTracks().get(0).getIdentifier();
				}
			}
		}

		try {
			return this.getLyrics(spotifyTackId);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public AudioLyrics getLyrics(String id) throws IOException {
		if (!this.tokenTracker.hasValidAccountCredentials()) {
			throw new IllegalArgumentException("Spotify spDc must be set");
		}

		var request = new HttpGet(CLIENT_API_BASE + "color-lyrics/v2/track/" + id + "?format=json&vocalRemoval=false");
		request.setHeader("User-Agent", USER_AGENT);
		request.setHeader("App-Platform", "WebPlayer");
		request.setHeader("Authorization", "Bearer " + this.tokenTracker.getAccountAccessToken());
		var json = LavaSrcTools.fetchResponseAsJson(this.httpInterfaceManager.getInterface(), request);
		if (json == null) {
			return null;
		}

		var lyrics = new ArrayList<AudioLyrics.Line>();
		for (var line : json.get("lyrics").get("lines").values()) {
			lyrics.add(new BasicAudioLyrics.BasicLine(
				Duration.ofMillis(line.get("startTimeMs").asLong(0)),
				null,
				line.get("words").text()
			));
		}

		return new BasicAudioLyrics("spotify", json.get("lyrics").get("providerDisplayName").textOrDefault("MusixMatch"), null, lyrics);
	}

	@Override
	public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
		var extendedAudioTrackInfo = super.decodeTrack(input);
		return new SpotifyAudioTrack(trackInfo,
			extendedAudioTrackInfo.albumName,
			extendedAudioTrackInfo.albumUrl,
			extendedAudioTrackInfo.artistUrl,
			extendedAudioTrackInfo.artistArtworkUrl,
			extendedAudioTrackInfo.previewUrl,
			extendedAudioTrackInfo.isPreview,
			this
		);
	}

	@Override
	@Nullable
	public AudioSearchResult loadSearch(@NotNull String query, @NotNull Set<AudioSearchResult.Type> types) {
		try {
			if (query.startsWith(SEARCH_PREFIX)) {
				return this.getAutocomplete(query.substring(SEARCH_PREFIX.length()), types);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return null;
	}

	@Override
	public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
		var identifier = reference.identifier;
		var preview = reference.identifier.startsWith(PREVIEW_PREFIX);
		return this.loadItem(preview ? identifier.substring(PREVIEW_PREFIX.length()) : identifier, preview);
	}

	public AudioItem loadItem(String identifier, boolean preview) {
		try {
			if (identifier.startsWith(SEARCH_PREFIX)) {
				return this.getSearch(identifier.substring(SEARCH_PREFIX.length()).trim(), preview);
			}

			if (identifier.startsWith(RECOMMENDATIONS_PREFIX)) {
				return this.getRecommendations(identifier.substring(RECOMMENDATIONS_PREFIX.length()).trim(), preview);
			}

			// If the identifier is a share URL, we need to follow the redirect to find out the real url behind it
			if (identifier.startsWith(SHARE_URL)) {
				var request = new HttpHead(identifier);
				request.setConfig(RequestConfig.custom().setRedirectsEnabled(false).build());
				try (var response = this.httpInterfaceManager.getInterface().execute(request)) {
					if (response.getStatusLine().getStatusCode() == 307) {
						var location = response.getFirstHeader("Location").getValue();
						if (location.startsWith("https://open.spotify.com/")) {
							return this.loadItem(location, preview);
						}
					}
					return null;
				}
			}

			var matcher = URL_PATTERN.matcher(identifier);
			if (!matcher.find()) {
				return null;
			}

			var id = matcher.group("identifier");
			switch (matcher.group("type")) {
				case "album":
					return this.getAlbum(id, preview);

				case "track":
					return this.getTrack(id, preview);

				case "playlist":
					return this.getPlaylist(id, preview);

				case "artist":
					return this.getArtist(id, preview);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return null;
	}

	public JsonBrowser getJson(String uri, boolean anonymous, boolean preferAnonymous) throws IOException {
		var request = new HttpGet(uri);
		var accessToken = anonymous ? this.tokenTracker.getAnonymousAccessToken() : this.tokenTracker.getAccessToken(preferAnonymous);
		request.addHeader("Authorization", "Bearer " + accessToken);
		return LavaSrcTools.fetchResponseAsJson(this.httpInterfaceManager.getInterface(), request);
	}

	private AudioSearchResult getAutocomplete(String query, Set<AudioSearchResult.Type> types) throws IOException {
		if (types.isEmpty()) {
			types = SEARCH_TYPES;
		}
		var url = WEB_API_BASE + "search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&type=" + types.stream().map(AudioSearchResult.Type::getName).collect(Collectors.joining(","));
		var json = this.getJson(url, false, false);
		if (json == null) {
			return AudioSearchResult.EMPTY;
		}

		var albums = new ArrayList<AudioPlaylist>();
		for (var album : json.get("albums").get("items").values()) {
			albums.add(new SpotifyAudioPlaylist(
				album.get("name").safeText(),
				Collections.emptyList(),
				ExtendedAudioPlaylist.Type.ALBUM,
				album.get("external_urls").get("spotify").text(),
				album.get("images").index(0).get("url").text(),
				album.get("artists").index(0).get("name").text(),
				(int) album.get("total_tracks").asLong(0)
			));
		}

		var artists = new ArrayList<AudioPlaylist>();
		for (var artist : json.get("artists").get("items").values()) {
			artists.add(new SpotifyAudioPlaylist(
				artist.get("name").safeText() + "'s Top Tracks",
				Collections.emptyList(),
				ExtendedAudioPlaylist.Type.ARTIST,
				artist.get("external_urls").get("spotify").text(),
				artist.get("images").index(0).get("url").text(),
				artist.get("name").text(),
				null
			));
		}

		var playlists = new ArrayList<AudioPlaylist>();
		for (var playlist : json.get("playlists").get("items").values()) {
			playlists.add(new SpotifyAudioPlaylist(
				playlist.get("name").safeText(),
				Collections.emptyList(),
				ExtendedAudioPlaylist.Type.PLAYLIST,
				playlist.get("external_urls").get("spotify").text(),
				playlist.get("images").index(0).get("url").text(),
				playlist.get("owner").get("display_name").text(),
				(int) playlist.get("tracks").get("total").asLong(0)
			));
		}

		var tracks = this.parseTrackItems(json.get("tracks"), false);

		return new BasicAudioSearchResult(tracks, albums, artists, playlists, new ArrayList<>());
	}

	public AudioItem getSearch(String query, boolean preview) throws IOException {
		if (shouldUsePartnerAPI()) {
			var variables = JsonBrowser.newMap();
			variables.put("searchTerm", query);
			variables.put("offset", 0);
			variables.put("limit", 10);
			variables.put("numberOfTopResults", 5);
			variables.put("includeAudiobooks", false);
			variables.put("includeArtistHasConcertsField", false);
			variables.put("includePreReleases", false);
			
			var json = queryPartnerAPI("searchDesktop", variables);
			
			if (json != null) {
				var searchV2 = json.get("data").get("searchV2");
				if (!searchV2.isNull()) {
					var tracks = new ArrayList<AudioTrack>();
					var tracksV2 = searchV2.get("tracksV2").get("items");
					
					for (var item : tracksV2.values()) {
						var track = parsePartnerTrack(item.get("item").get("data"), preview);
						if (track != null) {
							tracks.add(track);
						}
					}
					
					if (!tracks.isEmpty()) {
						return new BasicAudioPlaylist("Spotify Search: " + query, tracks, null, true);
					}
				}
			}
		}

		var json = this.getJson(WEB_API_BASE + "search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&type=track", false, false);
		if (json == null || json.get("tracks").get("items").values().isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		if (this.resolveArtistsInSearch) {
			var artistIds = json.get("tracks").get("items").values().stream().map(track -> track.get("artists").index(0).get("id").text()).collect(Collectors.joining(","));
			var artistJson = this.getJson(WEB_API_BASE + "artists?ids=" + artistIds, false, false);
			if (artistJson != null) {
				for (var artist : artistJson.get("artists").values()) {
					for (var track : json.get("tracks").get("items").values()) {
						if (track.get("artists").index(0).get("id").text().equals(artist.get("id").text())) {
							track.get("artists").index(0).put("images", artist.get("images"));
						}
					}
				}
			}
		}

		return new BasicAudioPlaylist("Spotify Search: " + query, this.parseTrackItems(json.get("tracks"), preview), null, true);
	}

	public AudioItem getRecommendations(String query, boolean preview) throws IOException {
		Matcher matcher = RADIO_MIX_QUERY_PATTERN.matcher(query);
		if (matcher.find()) {
			String seedType = matcher.group("seedType");
			String seed = matcher.group("seed");
			if (seedType.equals("isrc")) {
				AudioItem item = this.getSearch("isrc:" + seed, preview);
				if (item == AudioReference.NO_TRACK) {
					return AudioReference.NO_TRACK;
				}
				if (item instanceof AudioTrack) {
					seed = ((AudioTrack) item).getIdentifier();
					seedType = "track";
				} else if (item instanceof AudioPlaylist) {
					var playlist = (AudioPlaylist) item;
					if (!playlist.getTracks().isEmpty()) {
						seed = playlist.getTracks().get(0).getIdentifier();
						seedType = "track";
					} else {
						return AudioReference.NO_TRACK;
					}
				}
			}
			JsonBrowser rjson = this.getJson(CLIENT_API_BASE + "inspiredby-mix/v2/seed_to_playlist/spotify:" + seedType + ":" + seed + "?response-format=json", true, this.preferAnonymousToken);
			JsonBrowser mediaItems = rjson.get("mediaItems");
			if (mediaItems.isList() && mediaItems.values().size() > 0) {
				String playlistId = mediaItems.index(0).get("uri").text().split(":")[2];
				return this.getPlaylist(playlistId, preview);
			}

		}
		var json = this.getJson(WEB_API_BASE + "recommendations?" + query, false, false);
		if (json == null || json.get("tracks").values().isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		return new SpotifyAudioPlaylist("Spotify Recommendations:", this.parseTracks(json, preview), ExtendedAudioPlaylist.Type.RECOMMENDATIONS, null, null, null, null);
	}

	public AudioItem getAlbum(String id, boolean preview) throws IOException {
		if (shouldUsePartnerAPI()) {
			return getAlbumFromPartnerAPI(id, preview);
		} else {
			return getAlbumFromWebAPI(id, preview);
		}
	}

	private AudioItem getAlbumFromPartnerAPI(String id, boolean preview) throws IOException {
		try {
			var variables = JsonBrowser.newMap();
			variables.put("uri", "spotify:album:" + id);
			variables.put("locale", "");
			variables.put("offset", 0);
			variables.put("limit", 50);
			variables.put("enableWatchFeedEntrypoint", false);

			var json = queryPartnerAPI("getAlbum", variables);

			if (json == null) {
				log.debug("Partner API returned null for album {}, falling back to Web API", id);
				return getAlbumFromWebAPI(id, preview);
			}

			var albumData = json.get("data").get("albumUnion");
			if (albumData.isNull()) {
				return AudioReference.NO_TRACK;
			}

			return parsePartnerAlbum(albumData, preview);

		} catch (Exception e) {
			log.warn("Failed to get album from Partner API, falling back to Web API: {}", e.getMessage());
			return getAlbumFromWebAPI(id, preview);
		}
	}

	private AudioItem getAlbumFromWebAPI(String id, boolean preview) throws IOException {
		var json = this.getJson(WEB_API_BASE + "albums/" + id, false, this.preferAnonymousToken);
		if (json == null) {
			return AudioReference.NO_TRACK;
		}

		var artistJson = this.getJson(WEB_API_BASE + "artists/" + json.get("artists").index(0).get("id").text(), false, this.preferAnonymousToken);
		if (artistJson == null) {
			artistJson = JsonBrowser.newMap();
		}

		var tracks = new ArrayList<AudioTrack>();
		JsonBrowser page;
		var offset = 0;
		var pages = 0;
		do {
			page = this.getJson(WEB_API_BASE + "albums/" + id + "/tracks?limit=" + ALBUM_MAX_PAGE_ITEMS + "&offset=" + offset, false, this.preferAnonymousToken);
			offset += ALBUM_MAX_PAGE_ITEMS;

			var tracksPage = this.getJson(WEB_API_BASE + "tracks/?ids=" + page.get("items").values().stream().map(track -> track.get("id").text()).collect(Collectors.joining(",")), false, this.preferAnonymousToken);

			for (var track : tracksPage.get("tracks").values()) {
				var albumJson = JsonBrowser.newMap();
				albumJson.put("external_urls", json.get("external_urls"));
				albumJson.put("name", json.get("name"));
				albumJson.put("images", json.get("images"));
				track.put("album", albumJson);

				track.get("artists").index(0).put("images", artistJson.get("images"));
			}

			tracks.addAll(this.parseTracks(tracksPage, preview));
		}
		while (page.get("next").text() != null && ++pages < this.albumPageLimit);

		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		return new SpotifyAudioPlaylist(json.get("name").safeText(), tracks, ExtendedAudioPlaylist.Type.ALBUM, json.get("external_urls").get("spotify").text(), json.get("images").index(0).get("url").text(), json.get("artists").index(0).get("name").text(), (int) json.get("total_tracks").asLong(0));
	}

	public AudioItem getPlaylist(String id, boolean preview) throws IOException {
		if (shouldUsePartnerAPI()) {
			return getPlaylistFromPartnerAPI(id, preview);
		} else {
			return getPlaylistFromWebAPI(id, preview);
		}
	}

	private AudioItem getPlaylistFromPartnerAPI(String id, boolean preview) throws IOException {
		try {
			var variables = JsonBrowser.newMap();
			variables.put("uri", "spotify:playlist:" + id);
			variables.put("offset", 0);
			variables.put("limit", 100);
			variables.put("enableWatchFeedEntrypoint", false);

			var json = queryPartnerAPI("getPlaylist", variables);

			if (json == null) {
				log.debug("Partner API returned null for playlist {}, falling back to Web API", id);
				return getPlaylistFromWebAPI(id, preview);
			}

			var playlistData = json.get("data").get("playlistV2");
			if (playlistData.isNull()) {
				return AudioReference.NO_TRACK;
			}

			return parsePartnerPlaylist(playlistData, preview);

		} catch (Exception e) {
			log.warn("Failed to get playlist from Partner API, falling back to Web API: {}", e.getMessage());
			return getPlaylistFromWebAPI(id, preview);
		}
	}

	private AudioItem getPlaylistFromWebAPI(String id, boolean preview) throws IOException {
		// autogenerated playlists seem to start with "37i9dQZ" and are not accessible without an anonymous token lol
		var anonymous = id.startsWith("37i9dQZ");

		var json = this.getJson(WEB_API_BASE + "playlists/" + id, anonymous, this.preferAnonymousToken);
		if (json == null) {
			return AudioReference.NO_TRACK;
		}

		var tracks = new ArrayList<AudioTrack>();
		JsonBrowser page;
		var offset = 0;
		var pages = 0;
		do {
			page = this.getJson(WEB_API_BASE + "playlists/" + id + "/tracks?limit=" + PLAYLIST_MAX_PAGE_ITEMS + "&offset=" + offset, anonymous, this.preferAnonymousToken);
			offset += PLAYLIST_MAX_PAGE_ITEMS;

			for (var value : page.get("items").values()) {
				var track = value.get("track");
				if (track.isNull() || track.get("type").text().equals("episode") || (!this.localFiles && track.get("is_local").asBoolean(false))) {
					continue;
				}

				tracks.add(this.parseTrack(track, preview));
			}

		}
		while (page.get("next").text() != null && ++pages < this.playlistPageLimit);

		return new SpotifyAudioPlaylist(json.get("name").safeText(), tracks, ExtendedAudioPlaylist.Type.PLAYLIST, json.get("external_urls").get("spotify").text(), json.get("images").index(0).get("url").text(), json.get("owner").get("display_name").text(), (int) json.get("tracks").get("total").asLong(0));
	}

	public AudioItem getArtist(String id, boolean preview) throws IOException {
		var json = this.getJson(WEB_API_BASE + "artists/" + id, false, this.preferAnonymousToken);
		if (json == null) {
			return AudioReference.NO_TRACK;
		}

		var tracksJson = this.getJson(WEB_API_BASE + "artists/" + id + "/top-tracks?market=" + this.countryCode, false, this.preferAnonymousToken);
		if (tracksJson == null || tracksJson.get("tracks").values().isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		for (var track : tracksJson.get("tracks").values()) {
			track.get("artists").index(0).put("images", json.get("images"));
		}

		return new SpotifyAudioPlaylist(json.get("name").safeText() + "'s Top Tracks", this.parseTracks(tracksJson, preview), ExtendedAudioPlaylist.Type.ARTIST, json.get("external_urls").get("spotify").text(), json.get("images").index(0).get("url").text(), json.get("name").text(), (int) tracksJson.get("tracks").get("total").asLong(0));
	}

	public AudioItem getTrack(String id, boolean preview) throws IOException {
		if (shouldUsePartnerAPI()) {
			return getTrackFromPartnerAPI(id, preview);
		} else {
			return getTrackFromWebAPI(id, preview);
		}
	}

	private AudioItem getTrackFromPartnerAPI(String id, boolean preview) throws IOException {
		try {
			var variables = JsonBrowser.newMap();
			variables.put("uri", "spotify:track:" + id);
			variables.put("locale", "");
			variables.put("includePrerelease", false);
			variables.put("enableWatchFeedEntrypoint", false);

			var json = queryPartnerAPI("getTrack", variables);

			if (json == null) {
				log.debug("Partner API returned null for track {}, falling back to Web API", id);
				return getTrackFromWebAPI(id, preview);
			}

			var trackData = json.get("data").get("trackUnion");
			if (trackData.isNull()) {
				return AudioReference.NO_TRACK;
			}

			return parsePartnerTrack(trackData, preview);

		} catch (Exception e) {
			log.warn("Failed to get track from Partner API, falling back to Web API: {}", e.getMessage());
			return getTrackFromWebAPI(id, preview);
		}
	}

	private AudioItem getTrackFromWebAPI(String id, boolean preview) throws IOException {
		var json = this.getJson(WEB_API_BASE + "tracks/" + id, false, this.preferAnonymousToken);
		if (json == null) {
			return AudioReference.NO_TRACK;
		}

		var artistJson = this.getJson(WEB_API_BASE + "artists/" + json.get("artists").index(0).get("id").text(), false, this.preferAnonymousToken);
		if (artistJson != null) {
			json.get("artists").index(0).put("images", artistJson.get("images"));
		}

		return this.parseTrack(json, preview);
	}

	private List<AudioTrack> parseTracks(JsonBrowser json, boolean preview) {
		var tracks = new ArrayList<AudioTrack>();
		for (var value : json.get("tracks").values()) {
			tracks.add(this.parseTrack(value, preview));
		}
		return tracks;
	}

	private List<AudioTrack> parseTrackItems(JsonBrowser json, boolean preview) {
		var tracks = new ArrayList<AudioTrack>();
		for (var value : json.get("items").values()) {
			if (value.get("is_local").asBoolean(false)) {
				continue;
			}
			tracks.add(this.parseTrack(value, preview));
		}
		return tracks;
	}

	private AudioTrack parseTrack(JsonBrowser json, boolean preview) {
		return new SpotifyAudioTrack(
			new AudioTrackInfo(
				json.get("name").safeText(),
				json.get("artists").index(0).get("name").safeText().isEmpty() ? "Unknown" : json.get("artists").index(0).get("name").safeText(),
				preview ? PREVIEW_LENGTH : json.get("duration_ms").asLong(0),
				json.get("id").text() != null ? json.get("id").text() : "local",
				false,
				json.get("external_urls").get("spotify").text(),
				json.get("album").get("images").index(0).get("url").text(),
				json.get("external_ids").get("isrc").text()
			),
			json.get("album").get("name").text(),
			json.get("album").get("external_urls").get("spotify").text(),
			json.get("artists").index(0).get("external_urls").get("spotify").text(),
			json.get("artists").index(0).get("images").index(0).get("url").text(),
			json.get("preview_url").text(),
			preview,
			this
		);
	}

	@Override
	public void shutdown() {
		try {
			this.httpInterfaceManager.close();
		} catch (IOException e) {
			log.error("Failed to close HTTP interface manager", e);
		}
	}

	@Override
	public void configureRequests(Function<RequestConfig, RequestConfig> configurator) {
		this.httpInterfaceManager.configureRequests(configurator);
	}

	@Override
	public void configureBuilder(Consumer<HttpClientBuilder> configurator) {
		this.httpInterfaceManager.configureBuilder(configurator);
	}
}