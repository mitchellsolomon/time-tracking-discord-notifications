package com.rmsolo.timetrackingdiscord;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import com.rmsolo.timetrackingdiscord.data.Produce;
import com.rmsolo.timetrackingdiscord.data.Tab;
import com.rmsolo.timetrackingdiscord.data.TimeTrackingReader;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneScapeProfile;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
@PluginDescriptor(
	name = "Time Tracking Discord Notifications",
	description = "Sends Time Tracking ready times (herbs, Hespori, bird houses, ...) to a self-hosted server that posts Discord notifications",
	tags = {"time", "tracking", "farming", "birdhouse", "hespori", "herb", "discord", "notifications"}
)
public class TimeTrackingDiscordPlugin extends Plugin
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final int SYNC_INTERVAL_SECONDS = 60;
	// re-send unchanged snapshots now and then in case the server lost them
	private static final long RESEND_SECONDS = 15 * 60;
	private static final String TIMERS_ACCOUNT = "RuneLite timers";
	private static final int MAX_ACCOUNT_LENGTH = 32;

	@Inject
	private TimeTrackingDiscordConfig config;

	@Inject
	private TimeTrackingReader reader;

	@Inject
	private ConfigManager configManager;

	@Inject
	private OkHttpClient httpClient;

	@Inject
	private Gson gson;

	@Inject
	private ScheduledExecutorService executor;

	private ScheduledFuture<?> syncFuture;

	// account -> last snapshot the server accepted, and when
	private final Map<String, String> lastSent = new ConcurrentHashMap<>();
	private final Map<String, Long> lastSentAt = new ConcurrentHashMap<>();

	@Provides
	TimeTrackingDiscordConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(TimeTrackingDiscordConfig.class);
	}

	@Override
	protected void startUp()
	{
		syncFuture = executor.scheduleWithFixedDelay(this::syncSafely, 5, SYNC_INTERVAL_SECONDS, TimeUnit.SECONDS);
	}

	@Override
	protected void shutDown()
	{
		if (syncFuture != null)
		{
			syncFuture.cancel(false);
			syncFuture = null;
		}
		lastSent.clear();
		lastSentAt.clear();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (TimeTrackingDiscordConfig.GROUP.equals(event.getGroup()))
		{
			// settings changed: push fresh snapshots straight away
			lastSent.clear();
			executor.execute(this::syncSafely);
		}
	}

	private void syncSafely()
	{
		try
		{
			sync();
		}
		catch (RuntimeException e)
		{
			log.debug("Time tracking sync failed", e);
		}
	}

	private void sync()
	{
		if (!config.allowSync())
		{
			return;
		}

		HttpUrl url = HttpUrl.parse(config.serverUrl().trim());
		String key = config.apiKey().trim();
		if (url == null || key.isEmpty())
		{
			return;
		}

		for (RuneScapeProfile profile : configManager.getRSProfiles())
		{
			if (profile.getDisplayName() == null || profile.getDisplayName().isEmpty())
			{
				continue;
			}
			send(url, key, accountName(profile), buildProfileTimers(profile));
		}

		if (config.timers())
		{
			send(url, key, TIMERS_ACCOUNT, buildCustomTimers());
		}
	}

	JsonArray buildProfileTimers(RuneScapeProfile profile)
	{
		String key = profile.getKey();
		boolean leagues = profile.getType().name().contains("LEAGUE");
		boolean soonest = config.completionMode() == CompletionMode.FIRST;
		JsonArray timers = new JsonArray();

		if (config.birdHouses())
		{
			add(timers, "birdhouses", "Bird houses", "Your bird houses are ready to be dismantled.",
				reader.getBirdHouseCompletion(key));
		}
		if (config.hespori())
		{
			add(timers, "hespori", "Hespori", "Hespori is fully grown and ready to fight at the Farming Guild.",
				reader.getHesporiCompletion(key, leagues));
		}
		if (config.farmingContract())
		{
			Produce contract = reader.getContract(key);
			if (contract != null)
			{
				add(timers, "contract", "Farming contract",
					"Your farming contract crop (" + contract.getName() + ") is ready to harvest.",
					reader.getContractCompletion(key, contract, leagues));
			}
		}

		addCategory(timers, config.herbs(), Tab.HERB, key, soonest, leagues);
		addCategory(timers, config.allotments(), Tab.ALLOTMENT, key, soonest, leagues);
		addCategory(timers, config.flowers(), Tab.FLOWER, key, soonest, leagues);
		addCategory(timers, config.trees(), Tab.TREE, key, soonest, leagues);
		addCategory(timers, config.fruitTrees(), Tab.FRUIT_TREE, key, soonest, leagues);
		addCategory(timers, config.hops(), Tab.HOPS, key, soonest, leagues);
		addCategory(timers, config.bushes(), Tab.BUSH, key, soonest, leagues);
		addCategory(timers, config.grapes(), Tab.GRAPE, key, soonest, leagues);
		addCategory(timers, config.special(), Tab.SPECIAL, key, soonest, leagues);
		return timers;
	}

	private void addCategory(JsonArray timers, boolean enabled, Tab tab, String profile, boolean soonest, boolean leagues)
	{
		if (!enabled)
		{
			return;
		}
		String message = soonest
			? "One of your " + tab.getName().toLowerCase(Locale.ENGLISH) + " is ready."
			: "All of your " + tab.getName().toLowerCase(Locale.ENGLISH) + " are ready.";
		add(timers, tab.name().toLowerCase(Locale.ENGLISH), tab.getName(), message,
			reader.getCategoryCompletion(profile, tab, soonest, leagues));
	}

	private JsonArray buildCustomTimers()
	{
		JsonArray timers = new JsonArray();
		List<TimeTrackingReader.CustomTimer> running = reader.getRunningTimers();
		for (int i = 0; i < running.size(); i++)
		{
			TimeTrackingReader.CustomTimer timer = running.get(i);
			String name = Text.removeTags(timer.getName());
			if (name.length() > 60)
			{
				name = name.substring(0, 60);
			}
			add(timers, "timer-" + i, "Timer: " + name, "Your timer \"" + name + "\" has finished.", timer.getReadyAt());
		}
		return timers;
	}

	private static void add(JsonArray timers, String id, String label, String message, long readyAt)
	{
		// 0 = already ready, -1 = unknown or nothing growing: no future alert to schedule
		if (readyAt <= Instant.now().getEpochSecond())
		{
			return;
		}
		JsonObject timer = new JsonObject();
		timer.addProperty("id", id);
		timer.addProperty("label", label);
		timer.addProperty("message", message);
		timer.addProperty("ready_at", readyAt);
		timers.add(timer);
	}

	private static String accountName(RuneScapeProfile profile)
	{
		String name = profile.getDisplayName();
		if (profile.getType() != RuneScapeProfileType.STANDARD)
		{
			name += " (" + Text.titleCase(profile.getType()) + ")";
		}
		return name.length() > MAX_ACCOUNT_LENGTH ? name.substring(0, MAX_ACCOUNT_LENGTH) : name;
	}

	private void send(HttpUrl url, String apiKey, String account, JsonArray timers)
	{
		String snapshot = timers.toString();
		long now = Instant.now().getEpochSecond();
		if (snapshot.equals(lastSent.get(account)) && now - lastSentAt.getOrDefault(account, 0L) < RESEND_SECONDS)
		{
			return;
		}

		JsonObject payload = new JsonObject();
		payload.addProperty("account", account);
		payload.addProperty("synced_at", now);
		payload.add("timers", timers);

		Request request = new Request.Builder()
			.url(url)
			.header("Authorization", "Bearer " + apiKey)
			.post(RequestBody.create(JSON, gson.toJson(payload)))
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Time tracking sync for {} failed: {}", account, e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response ignored = response)
				{
					if (response.isSuccessful())
					{
						lastSent.put(account, snapshot);
						lastSentAt.put(account, Instant.now().getEpochSecond());
					}
					else
					{
						log.debug("Time tracking sync for {} returned HTTP {}", account, response.code());
					}
				}
			}
		});
	}
}
