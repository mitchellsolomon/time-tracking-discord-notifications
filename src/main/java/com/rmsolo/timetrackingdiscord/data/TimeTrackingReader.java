package com.rmsolo.timetrackingdiscord.data;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.config.ConfigManager;

/**
 * Read-only view of the data RuneLite's Time Tracking plugin saves to the RuneScape profile
 * config. Completion times use the same rules as Time Tracking: {@code -1} means unknown or
 * nothing planted, {@code 0} means already ready, anything else is the epoch second it will be ready.
 */
@Singleton
public class TimeTrackingReader
{
	public static final String CONFIG_GROUP = "timetracking";
	private static final String AUTOWEED = "autoweed";
	private static final String FARM_TICK_OFFSET = "farmTickOffset";
	private static final String FARM_TICK_OFFSET_PRECISION = "farmTickOffsetPrecision";
	private static final String BIRD_HOUSE = "birdhouse";
	private static final String CONTRACT = "contract";
	private static final String TIMERS = "timers";

	private static final int FARMING_GUILD_REGION_ID = 4922;
	// average time taken to harvest 10 birds, matching Time Tracking
	private static final int BIRD_HOUSE_DURATION = 50 * 60;
	private static final int BIRD_HOUSE_TYPES = 9;
	private static final int[] BIRD_HOUSE_VARPS = {
		VarPlayerID.BIRDHOUSE_TRANSMIT_A, VarPlayerID.BIRDHOUSE_TRANSMIT_B,
		VarPlayerID.BIRDHOUSE_TRANSMIT_C, VarPlayerID.BIRDHOUSE_TRANSMIT_D
	};

	/**
	 * Looks up a raw config value; {@code profile} is null for global (non-profile) keys.
	 */
	@FunctionalInterface
	interface ConfigSource
	{
		@Nullable
		String get(String group, @Nullable String profile, String key);
	}

	private final ConfigSource config;
	private final Gson gson;
	private final FarmingWorld farmingWorld = new FarmingWorld();

	@Value
	public static class CustomTimer
	{
		String name;
		long readyAt;
	}

	@Inject
	TimeTrackingReader(ConfigManager configManager, Gson gson)
	{
		this((group, profile, key) -> profile == null
			? configManager.getConfiguration(group, key)
			: configManager.getConfiguration(group, profile, key), gson);
	}

	TimeTrackingReader(ConfigSource config, Gson gson)
	{
		this.config = config;
		this.gson = gson;
	}

	/**
	 * Completion time for every patch shown on a Time Tracking farming tab.
	 *
	 * @param soonest true to report when the first patch is ready, false for when all are ready
	 */
	public long getCategoryCompletion(String profile, Tab tab, boolean soonest, boolean leagues)
	{
		Set<FarmingPatch> patches = farmingWorld.getTabs().getOrDefault(tab, Collections.emptySet());
		long extremum = soonest ? Long.MAX_VALUE : 0;
		boolean allUnknown = true;
		boolean allEmpty = true;

		for (FarmingPatch patch : patches)
		{
			PatchPrediction prediction = predictPatch(patch, profile, leagues);
			if (prediction == null || prediction.getProduce().getItemID() < 0)
			{
				continue;
			}
			allUnknown = false;

			if (prediction.getProduce() != Produce.WEEDS
				&& prediction.getProduce() != Produce.SCARECROW
				&& prediction.getCropState() != CropState.EMPTY
				&& prediction.getCropState() != CropState.FILLING)
			{
				allEmpty = false;
				extremum = soonest
					? Math.min(extremum, prediction.getDoneEstimate())
					: Math.max(extremum, prediction.getDoneEstimate());
			}
		}

		if (allUnknown || allEmpty)
		{
			return -1;
		}
		return extremum <= Instant.now().getEpochSecond() ? 0 : extremum;
	}

	/**
	 * Completion time of the Hespori patch in the Farming Guild; once it is ready it can be fought.
	 */
	public long getHesporiCompletion(String profile, boolean leagues)
	{
		for (FarmingPatch patch : farmingWorld.getTabs().getOrDefault(Tab.SPECIAL, Collections.emptySet()))
		{
			if (patch.getImplementation() != PatchImplementation.HESPORI)
			{
				continue;
			}

			PatchPrediction prediction = predictPatch(patch, profile, leagues);
			if (prediction == null || prediction.getProduce() != Produce.HESPORI)
			{
				return -1;
			}
			return prediction.getDoneEstimate() <= Instant.now().getEpochSecond() ? 0 : prediction.getDoneEstimate();
		}
		return -1;
	}

	public long getBirdHouseCompletion(String profile)
	{
		boolean anyData = false;
		boolean anySeeded = false;
		long max = 0;

		for (int varp : BIRD_HOUSE_VARPS)
		{
			String[] parts = split(config.get(CONFIG_GROUP, profile, BIRD_HOUSE + "." + varp));
			if (parts == null)
			{
				continue;
			}

			try
			{
				int value = Integer.parseInt(parts[0]);
				long timestamp = Long.parseLong(parts[1]);
				anyData = true;
				// 3, 6, 9... are seeded bird houses; see Time Tracking's BirdHouseState
				if (value > 0 && value <= BIRD_HOUSE_TYPES * 3 && value % 3 == 0)
				{
					anySeeded = true;
					max = Math.max(max, timestamp + BIRD_HOUSE_DURATION);
				}
			}
			catch (NumberFormatException e)
			{
				// ignored
			}
		}

		if (!anyData || !anySeeded)
		{
			return -1;
		}
		return max <= Instant.now().getEpochSecond() ? 0 : max;
	}

	@Nullable
	public Produce getContract(String profile)
	{
		try
		{
			String stored = config.get(CONFIG_GROUP, profile, CONTRACT);
			return stored == null ? null : Produce.getByItemID(Integer.parseInt(stored));
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	/**
	 * Soonest completion of a Farming Guild patch growing the current farming contract crop.
	 */
	public long getContractCompletion(String profile, Produce contract, boolean leagues)
	{
		long soonest = Long.MAX_VALUE;
		for (FarmingPatch patch : farmingWorld.getFarmingGuildRegion().getPatches())
		{
			if (patch.getImplementation() != contract.getPatchImplementation())
			{
				continue;
			}

			PatchPrediction prediction = predictPatch(patch, profile, leagues);
			// Herbs always turn into ANYHERB when dead, but a dead crop never completes
			if (prediction == null || prediction.getProduce() != contract
				|| (prediction.getCropState() != CropState.GROWING && prediction.getCropState() != CropState.HARVESTABLE))
			{
				continue;
			}
			soonest = Math.min(soonest, prediction.getDoneEstimate());
		}

		if (soonest == Long.MAX_VALUE)
		{
			return -1;
		}
		return soonest <= Instant.now().getEpochSecond() ? 0 : soonest;
	}

	/**
	 * Running countdown timers from the Time Tracking "Timers & Stopwatches" tab. These are
	 * stored globally rather than per RuneScape profile.
	 */
	public List<CustomTimer> getRunningTimers()
	{
		List<CustomTimer> result = new ArrayList<>();
		String json = config.get(CONFIG_GROUP, null, TIMERS);
		if (json == null || json.isEmpty())
		{
			return result;
		}

		try
		{
			JsonArray timers = gson.fromJson(json, JsonArray.class);
			for (JsonElement element : timers)
			{
				JsonObject timer = element.getAsJsonObject();
				if (!timer.has("active") || !timer.get("active").getAsBoolean())
				{
					continue;
				}
				long remaining = timer.get("remaining").getAsLong();
				long lastUpdate = timer.get("lastUpdate").getAsLong();
				String name = timer.has("name") && !timer.get("name").isJsonNull() ? timer.get("name").getAsString() : "Timer";
				if (remaining > 0)
				{
					result.add(new CustomTimer(name, lastUpdate + remaining));
				}
			}
		}
		catch (RuntimeException e)
		{
			// malformed or changed format; treat as no timers
		}
		return result;
	}

	/**
	 * Mirrors FarmingTracker#predictPatch, reading from the given profile.
	 */
	@Nullable
	PatchPrediction predictPatch(FarmingPatch patch, String profile, boolean leagues)
	{
		long unixNow = Instant.now().getEpochSecond();

		boolean autoweed = Integer.toString(Autoweed.ON.ordinal())
			.equals(config.get(CONFIG_GROUP, profile, AUTOWEED));

		String[] parts = split(config.get(CONFIG_GROUP, profile, patch.configKey()));
		if (parts == null)
		{
			return null;
		}

		long unixTime;
		int value;
		try
		{
			value = Integer.parseInt(parts[0]);
			unixTime = Long.parseLong(parts[1]);
		}
		catch (NumberFormatException e)
		{
			return null;
		}

		if (unixTime <= 0)
		{
			return null;
		}

		PatchState state = patch.getImplementation().forVarbitValue(value);
		if (state == null)
		{
			return null;
		}

		int stage = state.getStage();
		int stages = state.getStages();
		int tickrate = state.getTickRate();

		// Farming ticks on leagues worlds are 1 minute instead of 5
		if (leagues)
		{
			tickrate = tickrate / 5;
		}

		if (autoweed && state.getProduce() == Produce.WEEDS)
		{
			stage = 0;
			stages = 1;
			tickrate = 0;
		}

		long doneEstimate = 0;
		if (tickrate > 0)
		{
			long tickNow = getTickTime(tickrate, 0, unixNow, profile);
			long tickTime = getTickTime(tickrate, 0, unixTime, profile);
			int delta = (int) (tickNow - tickTime) / (tickrate * 60);

			doneEstimate = getTickTime(tickrate, stages - 1 - stage, tickTime, profile);

			stage += delta;
			if (stage >= stages)
			{
				stage = stages - 1;
			}
		}

		return new PatchPrediction(state.getProduce(), state.getCropState(), doneEstimate, stage, stages);
	}

	/**
	 * Mirrors FarmingTracker#getTickTime, including the learned farming tick offset.
	 */
	long getTickTime(int tickRate, int ticks, long requestedTime, String profile)
	{
		Integer offsetPrecisionMins = getInt(profile, FARM_TICK_OFFSET_PRECISION);
		Integer offsetTimeMins = getInt(profile, FARM_TICK_OFFSET);

		//All offsets are negative but are stored as positive
		long calculatedOffsetTime = 0L;
		if (offsetPrecisionMins != null && offsetTimeMins != null && (offsetPrecisionMins >= tickRate || offsetPrecisionMins >= 40))
		{
			calculatedOffsetTime = (offsetTimeMins % tickRate) * 60;
		}

		long unixNow = requestedTime + calculatedOffsetTime;
		long timeOfCurrentTick = (unixNow - (unixNow % (tickRate * 60)));
		long timeOfGoalTick = timeOfCurrentTick + ((long) ticks * tickRate * 60);
		return timeOfGoalTick - calculatedOffsetTime;
	}

	@Nullable
	private Integer getInt(String profile, String key)
	{
		String value = config.get(CONFIG_GROUP, profile, key);
		if (value == null)
		{
			return null;
		}
		try
		{
			return Integer.parseInt(value);
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	@Nullable
	private static String[] split(@Nullable String stored)
	{
		if (stored == null)
		{
			return null;
		}
		String[] parts = stored.split(":");
		return parts.length == 2 ? parts : null;
	}
}
