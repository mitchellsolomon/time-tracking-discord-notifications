package com.rmsolo.timetrackingdiscord;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum CompletionMode
{
	ALL("All patches ready"),
	FIRST("First patch ready");

	private final String name;

	@Override
	public String toString()
	{
		return name;
	}
}
