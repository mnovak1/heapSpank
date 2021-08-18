package com.github.eostermueller.heapspank.leakyspank.console;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.commons.configuration2.ex.ConfigurationException;

import com.github.eostermueller.heapspank.leakyspank.JvmAttachException;
import com.github.eostermueller.heapspank.leakyspank.LeakySpankContext;
import com.github.eostermueller.heapspank.leakyspank.Model;
import com.github.eostermueller.heapspank.leakyspank.tools.JMapHisto;
import com.github.eostermueller.heapspank.leakyspank.tools.JMapHistoException;
import com.github.eostermueller.heapspank.leakyspank.tools.JMapHistoProcessWrapper;
import com.github.eostermueller.heapspank.leakyspank.tools.ProcessIdDoesNotExist;
import com.github.eostermueller.heapspank.leakyspank.tools.VirtualMachineWrapper;
import com.github.eostermueller.heapspank.util.LimitedSizeQueue;

public class LeakySpankConsole implements DisplayUpdateListener {

	private static final String VERSION = "v0.09";
	private static final String BANNER_FORMAT =     "  %4ds   heapSpank memory leak detector pid[%s] [%s]%n";
	private static final String BANNER_FORMAT_ALT = "# %4ds   heapSpank memory leak detector pid[%s] [%s] ##%n";
	private static final String INDENT = "\t";
	Queue<Model> jmapHistoOutputQueue = new ConcurrentLinkedQueue<Model>();
	JMapHistoRunner jMapHistoRunner = null;
	LeakySpankContext leakySpankContext = null;
	ConsoleView consoleView = null;
	int screenRefreshIntervalSeconds = -1;
	private int maxIterations_;
	private Config config;
	private long displayUpdatedTimestampMs;
	LimitedSizeQueue<String> debug = new LimitedSizeQueue<String>(10);

	public static void main(String args[]) throws InstantiationException,
			IllegalAccessException, ClassNotFoundException, JvmAttachException, JMapHistoException, ProcessIdDoesNotExist, MultiPropertyException, CommandLineParameterException {
		LeakySpankConsole leakySpankConsole = new LeakySpankConsole();
		Config config;
		try {
			ConfigFactory factory = new ConfigFactory();
			
			//JUnit tests should never use this.
			//We don't want haphazardly placed property files altering junit test behavior.
			factory.setDefaultConfigClass(ConfigFactory.APACHE_CONFIG_IMPL);
			
			config = factory.createNew(args);
			if (config != null) {
				leakySpankConsole.init(config);
				
				if (config.runSelfTestAndExit()) {
					try {
						String jmapResult = leakySpankConsole.jMapHistoRunner.getJMapHisto().selfTest();
						//show enough of the sample to convince user that jmap -histo data is flowing
						if (jmapResult!=null) {
							debug("Results [" + jmapResult.substring(0, 256) + "]");
							debug("** JMap -histo successful / RESULTS TRUNCATED **");
						} else {
							debug("## JMap -histo ERROR ##");
						}
					} finally {
						leakySpankConsole.jMapHistoRunner.shutdown();
					}
				} else
					leakySpankConsole.loopForever(leakySpankConsole.getConsoleView());
			} else {
				System.out.println("Fatal error.  unable to create configuration.");
				System.out.println( getUsage(args) );
			}
		} catch (Exception e) {
			if (e instanceof org.apache.commons.configuration2.ex.ConfigurationException) {
				ConfigurationException ce = (ConfigurationException)e;
				if (e.getMessage().contains("Could not locate")) {
					
				}
			}
			e.printStackTrace();
			System.out.println("\n");
			System.out.println(e.getMessage());
			System.out.println( getUsage(args) );
		}
	}

	private static String getUsage(String[] args) {
		StringBuilder sb = new StringBuilder();
		sb.append("\n");
		sb.append("\n");
		sb.append(INDENT + "-----------------------------------------------\n");
		sb.append(INDENT + "Usage for heapSpank Java memory leak detection.\n");
		sb.append("\n");
		sb.append(INDENT + INDENT + "java -jar heapSpank.jar <myPid>\n");
		sb.append("\n");
		sb.append(INDENT + "OR\n");
		sb.append("\n");
		sb.append(INDENT + INDENT + "java -jar heapSpank.jar <myPid> -selfTest\n");
		sb.append("\n");
		sb.append(INDENT + "OR\n");
		sb.append("\n");
		sb.append(INDENT + INDENT + "java -jar heapSpank.jar <myPid> -config <myConfig>\n");
		sb.append("\n");
		sb.append(INDENT + "WHERE\n");
		sb.append("\n");
		sb.append(INDENT + INDENT + "-- <myPid> is the process id (pid) of the java process to monitor for memory leaks.\n");
		sb.append(INDENT + INDENT + "           Run JAVA_HOME/bin/jps to display pids of all running JVMs.\n");
		sb.append("\n");
		sb.append(INDENT + INDENT + "-- <myConfig> is the full package and class name of a your custom class \n");
		sb.append(INDENT + INDENT + "       that implements com.github.eostermueller.heapspank.leakyspank.console.Config\n");
		sb.append("\n");
		sb.append(INDENT + INDENT + "-- The -selfTest parm will display troubleshooting info and immediately exist heapSpank.\n");
		return sb.toString();
	}

	private void loopForever(ConsoleView view) {

		try {
			System.setOut(new PrintStream(new BufferedOutputStream(
					new FileOutputStream(FileDescriptor.out)), false));
			int iterations = 0;
			
			while (!view.shouldExit() &&
					(jMapHistoRunner.failedExecutionCount.intValue() <=0) 
					) {
				clearConsole();

				Model m = this.jmapHistoOutputQueue.poll();
				if (m != null)
					this.leakySpankContext.addJMapHistoRun(m);
				printTopBar();
				view.printView();
				if (config.getLeakOutputFile() != null && !"".equals(config.getLeakOutputFile()))	{
					File output = new File(config.getLeakOutputFile());
					if (output.delete())	{
						output.createNewFile();
					}
					PrintWriter writer = new PrintWriter(output);
					writer.println("LKY%	#B-INC	#JMH	#I-INC	#R-INC	BYTES	INSTANCES	NUM	CLASS");
					writer.println(view.getMultiLineDisplayData());
					writer.flush();
					writer.close();
				}
				printDebug();
				printExceptions();
				System.out.flush();
				if (iterations >= maxIterations_ && maxIterations_ > 0) {
					break;
				}
				view.sleep((int) (screenRefreshIntervalSeconds * 1000));
				
				
					
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			if (jMapHistoRunner.failedExecutionCount.intValue()>0) {
				DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.FULL, Locale.getDefault() );

				String date = dateFormat.format(new Date());
				System.err.println("Process ID [" + this.getConfig().getPid() + "] died at [" + date + "!");
								
			}
			
			jMapHistoRunner.shutdown();
		}
	}

	private void printExceptions() {

		if (this.jMapHistoRunner.getFailedCount() > 0) {
			System.out.println("jmap -histo exception count fpr pid [" + this.jMapHistoRunner.getJMapHisto().getPid() + "]: [" + this.jMapHistoRunner.getFailedCount() + "]");
			System.out.println(this.jMapHistoRunner.getExceptionText());
		}
	}

	private void printDebug() {
		System.out.println("\n================================================================");
		for(String item : this.debug ){
		    System.out.println(item.toString());
		}
		
	}

	private void printTopBar() {
		long screenRefreshDelayMs = this.getConfig().getjMapCountPerWindow()
				* this.getConfig().getjMapHistoIntervalSeconds() * 1000;

		long estimatedRefreshTimestampMs = this.getDisplayUpdatedTimestampMs()
				+ screenRefreshDelayMs;
		// estimatedRefreshTimestampMs -= 2000;//correction
		long msUntilRefresh = estimatedRefreshTimestampMs
				- System.currentTimeMillis();
		double secondsUntilRefresh = Math.round(msUntilRefresh / 1000);
		int intSecondsUntilRefresh = (int) secondsUntilRefresh;
		if (intSecondsUntilRefresh < 6) {

			if (intSecondsUntilRefresh % 2 == 0)
				System.out.format(
						BANNER_FORMAT, 
						intSecondsUntilRefresh,
						""+this.getLeakySpankContext().getPid(),
						VERSION);
			else
				System.out.format(
						BANNER_FORMAT_ALT, 
						intSecondsUntilRefresh,
						""+this.getLeakySpankContext().getPid(),
						VERSION);
		} else
			System.out.format(
					BANNER_FORMAT, 
					intSecondsUntilRefresh, 
					""+this.getLeakySpankContext().getPid(),
					VERSION);
	}

	private void init(Config config) throws InstantiationException,
			IllegalAccessException, ClassNotFoundException, JvmAttachException, JMapHistoException, ProcessIdDoesNotExist {
		screenRefreshIntervalSeconds = config
				.getScreenRefreshIntervalSeconds();
		this.maxIterations_ = config.getMaxIterations();
		this.setLeakySpankContext(new LeakySpankContext(config.getPid(),
				config.getjMapHistoIntervalSeconds(), 
				config.getjMapCountPerWindow(), 
				config.getSuspectCountPerWindow()));

		this.getLeakySpankContext().setTopNSuspects(
				config.getSuspectCountPerWindow());
		this.getLeakySpankContext().setDebugDisplayQueue(debug);

		JMapHisto histo = new VirtualMachineWrapper("" +config.getPid());
		try {
			histo.selfTest();
		} catch (JMapHistoException e) {
			debug("Attempting backup plan because VirtualMachineWrapper did not work. [" + e.getCause().getMessage() + "][" + e.getCause().getClass().getName() + "]");
			histo = new JMapHistoProcessWrapper(""+config.getPid());
			try {
				histo.selfTest();
			} catch (JMapHistoException e1) {
				String error = "Giving up. Is JDK installed?  A JRE is not sufficent. Neither VirtualMachineWrapper nor JMapHistoProcessWrapper can execute jmap.  "; 
				debug(error);
				e1.setMessage(error);
				throw e1;
			}
		}
		
		jMapHistoRunner = new JMapHistoRunner(
				histo,
				config.getjMapHistoIntervalSeconds(),
				this.jmapHistoOutputQueue, 
				config.getClassNameExclusionFilter(),
				config.getJMapHistoLive()
				);

		jMapHistoRunner.launchJMapHistoExecutor();

		Class<ConsoleView> c = (Class<ConsoleView>) Class.forName(config
				.getViewClass());
		ConsoleView view = c.newInstance();
		view.setDisplayRowCount(config.getDisplayRowCount());
		view.setLeakySpankContext(this.getLeakySpankContext());
		view.setDisplayUpdateListener((DisplayUpdateListener) this);
		view.init();
		this.debug(String.format("just set view [%s]", view));
		this.debug(String.format("just set context [%s]",this.getLeakySpankContext()));
		this.setConsoleView(view);
		setConfig(config);
	}

	private void setConfig(Config config2) {
		this.config = config2;
	}

	private Config getConfig() {
		return this.config;
	}

	private static void debug(String msg) {
		System.out.println(DefaultView.HEAP_SPANK + msg);
	}

	/**
	 * @stolenFrom: https://github.com/patric-r/jvmtop/blob/1d38f
	 *              b29698cf396042a08be08e9a1024f95fd2a
	 *              /src/main/java/com/jvmtop/JvmTop.java
	 */
	private void clearConsole() {
		if (System.getProperty("os.name").contains("Windows")) {
			// hack
			System.out
					.printf("%n%n%n%n%n%n%n%n%n%n%n%n%n%n%n%n%n%n%n%n%n%n%n%n%n%n%n%n%n%n%n");
		} else if (System.getProperty("jvmtop.altClear") != null) {
			System.out.print('\f');
		} else {
			System.out.print(CLEAR_TERMINAL_ANSI_CMD);
		}
	}

	public ConsoleView getConsoleView() {
		return consoleView;
	}

	public void setConsoleView(ConsoleView consoleView) {
		this.consoleView = consoleView;
	}

	public LeakySpankContext getLeakySpankContext() {
		return this.leakySpankContext;
	}

	public void setLeakySpankContext(LeakySpankContext val) {
		this.leakySpankContext = val;
	}

	private final static String CLEAR_TERMINAL_ANSI_CMD = new String(
			new byte[] { (byte) 0x1b, (byte) 0x5b, (byte) 0x32, (byte) 0x4a,
					(byte) 0x1b, (byte) 0x5b, (byte) 0x48 });

	@Override
	public void updated() {
		this.setDisplayUpdatedTimestampMs(System.currentTimeMillis());
	}

	private void setDisplayUpdatedTimestampMs(long currentTimeMillis) {
		this.displayUpdatedTimestampMs = currentTimeMillis;
	}

	private long getDisplayUpdatedTimestampMs() {
		return this.displayUpdatedTimestampMs;
	}
}
