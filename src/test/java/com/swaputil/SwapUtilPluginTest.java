package com.swaputil;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class SwapUtilPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(SwapUtilPlugin.class);
		RuneLite.main(args);
	}
}
