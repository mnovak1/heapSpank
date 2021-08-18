package com.github.eostermueller.heapspank.leakyspank.console;

import java.io.File;
import java.util.List;

import com.github.eostermueller.heapspank.leakyspank.ClassNameFilter;

public interface Config {
	public static final String DEFAULT_FILE_NAME = "heapSpank.properties";

	public abstract int getScreenRefreshIntervalSeconds();

	public abstract void setScreenRefreshIntervalSeconds(
			int screenRefreshIntervalSeconds);

	int getjMapHistoIntervalSeconds();

	void setjMapHistoIntervalSeconds(int jMapHistoIntervalSeconds);

	void setjMapCountPerWindow(int jMapCountPerWindow);

	int getjMapCountPerWindow();

	void setSuspectCountPerWindow(int suspectCountPerWindow);

	int getSuspectCountPerWindow();

	public abstract long getPid();

	void setViewClass(String viewClass);

	String getViewClass();

	public abstract int getMaxIterations();

	void setArgs(String[] args) throws CommandLineParameterException;

	void setRunSelfTestAndExit(boolean runSelfTestOnly);

	boolean runSelfTestAndExit();

	public void setRegExExclusionFilter(String string);

	public String getRegExExclusionFilter();

	ClassNameFilter getClassNameExclusionFilter();
	
	public int getDisplayRowCount();
	public void setDisplayRowCount(int rows);
	public boolean getJMapHistoLive();
	public void setJMapHistoLive(boolean b);
	public String getLeakOutputFile();
	public void setLeakOutputFile(String output);
}