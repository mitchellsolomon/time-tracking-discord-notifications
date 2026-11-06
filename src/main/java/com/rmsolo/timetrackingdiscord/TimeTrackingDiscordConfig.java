package com.rmsolo.timetrackingdiscord;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(TimeTrackingDiscordConfig.GROUP)
public interface TimeTrackingDiscordConfig extends Config
{
	String GROUP = "timetrackingdiscord";

	@ConfigSection(name = "Connection", description = "Your self-hosted notification server", position = 0)
	String connectionSection = "connection";

	@ConfigSection(name = "Notifications", description = "Which Time Tracking categories send a Discord notification", position = 1)
	String categorySection = "categories";

	@ConfigItem(
		keyName = "allowSync",
		name = "Allow data sync to server",
		description = "Send your character name and Time Tracking ready times to the server below. Nothing is sent while this is off.",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
		section = connectionSection,
		position = 0
	)
	default boolean allowSync()
	{
		return false;
	}

	@ConfigItem(
		keyName = "serverUrl",
		name = "Server URL",
		description = "Address of your notification server, e.g. http://192.168.1.50:8787/sync",
		section = connectionSection,
		position = 1
	)
	default String serverUrl()
	{
		return "";
	}

	@ConfigItem(
		keyName = "apiKey",
		name = "API key",
		description = "Must match API_KEY on the server",
		section = connectionSection,
		secret = true,
		position = 2
	)
	default String apiKey()
	{
		return "";
	}

	@ConfigItem(
		keyName = "completionMode",
		name = "Patch groups ready when",
		description = "Notify when every patch in a category is ready, or as soon as the first one is",
		section = categorySection,
		position = 0
	)
	default CompletionMode completionMode()
	{
		return CompletionMode.ALL;
	}

	@ConfigItem(keyName = "birdHouses", name = "Bird houses", description = "Bird houses are ready to be dismantled", section = categorySection, position = 1)
	default boolean birdHouses()
	{
		return true;
	}

	@ConfigItem(keyName = "hespori", name = "Hespori", description = "Hespori is fully grown and ready to fight", section = categorySection, position = 2)
	default boolean hespori()
	{
		return true;
	}

	@ConfigItem(keyName = "herbs", name = "Herb patches", description = "Herb patches are ready to harvest", section = categorySection, position = 3)
	default boolean herbs()
	{
		return true;
	}

	@ConfigItem(keyName = "farmingContract", name = "Farming contract", description = "Your farming contract crop is ready to harvest", section = categorySection, position = 4)
	default boolean farmingContract()
	{
		return false;
	}

	@ConfigItem(keyName = "allotments", name = "Allotment patches", description = "Allotment patches are ready to harvest", section = categorySection, position = 5)
	default boolean allotments()
	{
		return false;
	}

	@ConfigItem(keyName = "flowers", name = "Flower patches", description = "Flower patches are ready to harvest", section = categorySection, position = 6)
	default boolean flowers()
	{
		return false;
	}

	@ConfigItem(keyName = "trees", name = "Tree patches", description = "Tree patches are fully grown", section = categorySection, position = 7)
	default boolean trees()
	{
		return false;
	}

	@ConfigItem(keyName = "fruitTrees", name = "Fruit tree patches", description = "Fruit tree patches are fully grown", section = categorySection, position = 8)
	default boolean fruitTrees()
	{
		return false;
	}

	@ConfigItem(keyName = "hops", name = "Hops patches", description = "Hops patches are ready to harvest", section = categorySection, position = 9)
	default boolean hops()
	{
		return false;
	}

	@ConfigItem(keyName = "bushes", name = "Bush patches", description = "Bush patches are ready to harvest", section = categorySection, position = 10)
	default boolean bushes()
	{
		return false;
	}

	@ConfigItem(keyName = "grapes", name = "Grape patches", description = "Grape vines are ready to harvest", section = categorySection, position = 11)
	default boolean grapes()
	{
		return false;
	}

	@ConfigItem(
		keyName = "special",
		name = "Special patches",
		description = "Everything on Time Tracking's Special tab: mushroom, cactus, seaweed, belladonna, coral, anima, compost bins and Hespori",
		section = categorySection,
		position = 12
	)
	default boolean special()
	{
		return false;
	}

	@ConfigItem(keyName = "timers", name = "Timers", description = "Countdown timers from the Timers & Stopwatches tab", section = categorySection, position = 13)
	default boolean timers()
	{
		return false;
	}
}
