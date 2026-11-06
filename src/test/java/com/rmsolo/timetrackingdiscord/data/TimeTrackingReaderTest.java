package com.rmsolo.timetrackingdiscord.data;

import com.google.gson.Gson;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import static org.junit.Assert.assertEquals;
import org.junit.Before;
import org.junit.Test;

public class TimeTrackingReaderTest
{
	private static final String PROFILE = "rsprofile.test";
	private static final String GROUP = TimeTrackingReader.CONFIG_GROUP;

	private final Map<String, String> stored = new HashMap<>();
	private TimeTrackingReader reader;
	private long now;

	@Before
	public void before()
	{
		stored.clear();
		reader = new TimeTrackingReader((group, profile, key) ->
			GROUP.equals(group) ? stored.get(profile + "/" + key) : null, new Gson());
		now = Instant.now().getEpochSecond();
	}

	private void store(String key, int value, long time)
	{
		stored.put(PROFILE + "/" + key, value + ":" + time);
	}

	@Test
	public void hesporiReadyAfterRemainingGrowthTicks()
	{
		// varbit 4 = Hespori just planted (stage 0 of 4), 640 minute growth ticks
		store("5021." + VarbitID.FARMING_TRANSMIT_J, 4, now);
		long tick = 640 * 60;
		assertEquals(now - now % tick + 3 * tick, reader.getHesporiCompletion(PROFILE, false));
	}

	@Test
	public void hesporiAlreadyGrownIsReady()
	{
		store("5021." + VarbitID.FARMING_TRANSMIT_J, 7, now);
		assertEquals(0, reader.getHesporiCompletion(PROFILE, false));
	}

	@Test
	public void unknownWithoutData()
	{
		assertEquals(-1, reader.getHesporiCompletion(PROFILE, false));
		assertEquals(-1, reader.getBirdHouseCompletion(PROFILE));
		assertEquals(-1, reader.getCategoryCompletion(PROFILE, Tab.HERB, false, false));
	}

	@Test
	public void herbCategoryUsesLatestOrSoonestPatch()
	{
		long tick = 20 * 60;
		// Farming Guild herb planted now (stage 0 of 5), Falador herb one tick in (stage 1)
		store("4922." + VarbitID.FARMING_TRANSMIT_E, 4, now);
		store("12083." + VarbitID.FARMING_TRANSMIT_D, 5, now);
		long base = now - now % tick;

		assertEquals(base + 4 * tick, reader.getCategoryCompletion(PROFILE, Tab.HERB, false, false));
		assertEquals(base + 3 * tick, reader.getCategoryCompletion(PROFILE, Tab.HERB, true, false));
	}

	@Test
	public void birdHousesReadyFiftyMinutesAfterSeeding()
	{
		store("birdhouse." + VarPlayerID.BIRDHOUSE_TRANSMIT_A, 3, now - 600);
		store("birdhouse." + VarPlayerID.BIRDHOUSE_TRANSMIT_B, 6, now);
		// built but not seeded
		store("birdhouse." + VarPlayerID.BIRDHOUSE_TRANSMIT_C, 4, now);
		assertEquals(now + 50 * 60, reader.getBirdHouseCompletion(PROFILE));
	}

	@Test
	public void runningTimersOnly()
	{
		stored.put("null/timers",
			"[{\"name\":\"Birdhouse run\",\"lastUpdate\":" + now + ",\"active\":true,\"duration\":600,\"remaining\":300,\"loop\":false},"
				+ "{\"name\":\"Paused\",\"lastUpdate\":" + now + ",\"active\":false,\"duration\":600,\"remaining\":600,\"loop\":false}]");
		assertEquals(1, reader.getRunningTimers().size());
		assertEquals(now + 300, reader.getRunningTimers().get(0).getReadyAt());
	}
}
