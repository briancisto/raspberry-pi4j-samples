package nmea.consumers.client;

import nmea.api.Multiplexer;
import nmea.api.NMEAClient;
import nmea.api.NMEAEvent;
import nmea.api.NMEAReader;
import nmea.consumers.reader.LSM303Reader;

/**
 * Reads a LSM303 sensor, and produces a valid NMEA sentence.
 * Pitch & Roll
 */
public class LSM303Client extends NMEAClient {
	public LSM303Client() {
		this(null, null, null);
	}

	public LSM303Client(Multiplexer mux) {
		this(null, null, mux);
	}

	public LSM303Client(String s[], String[] sa) {
		this(s, sa, null);
	}

	public LSM303Client(String s[], String[] sa, Multiplexer mux) {
		super(s, sa, mux);
		this.verbose = ("true".equals(System.getProperty("lsm303.data.verbose", "false")));
	}

	public String getSpecificDevicePrefix() {
		String dp = "";
		NMEAReader reader = this.getReader();
		if (reader != null && reader instanceof LSM303Reader) {
			dp = ((LSM303Reader)reader).getDevicePrefix();
		}
		return dp;
	}

	public void setSpecificDevicePrefix(String dp) {
		NMEAReader reader = this.getReader();
		if (reader != null && reader instanceof LSM303Reader) {
			((LSM303Reader)reader).setDevicePrefix(dp);
		}
	}

	@Override
	public void dataDetectedEvent(NMEAEvent e) {
		if (verbose)
			System.out.println(">> Received from LSM303:" + e.getContent());
		if (multiplexer != null) {
			multiplexer.onData(e.getContent());
		}
	}

	private static LSM303Client nmeaClient = null;

	public static class LSM303Bean implements ClientBean {
		private String cls;
		private String type = "lsm303";
		private boolean verbose;
		private String[] deviceFilters;
		private String[] sentenceFilters;
		private String devicePrefix;

		public LSM303Bean(LSM303Client instance) {
			cls = instance.getClass().getName();
			verbose = instance.isVerbose();
			deviceFilters = instance.getDevicePrefix();
			sentenceFilters = instance.getSentenceArray();
			devicePrefix = instance.getSpecificDevicePrefix();
		}

		@Override
		public String getType() {
			return this.type;
		}

		@Override
		public boolean getVerbose() {
			return this.verbose;
		}

		@Override
		public String[] getDeviceFilters() { return this.deviceFilters; }

		@Override
		public String[] getSentenceFilters() { return this.sentenceFilters; }

		public String getDevicePrefix() { return this.devicePrefix; }
	}

	@Override
	public Object getBean() {
		return new LSM303Bean(this);
	}

	public static void main(String[] args) {
		System.out.println("LSM303Client invoked with " + args.length + " Parameter(s).");
		for (String s : args)
			System.out.println("LSM303Client prm:" + s);

		nmeaClient = new LSM303Client();

		Runtime.getRuntime().addShutdownHook(new Thread("LSM303Client shutdown hook") {
			public void run() {
				System.out.println("Shutting down nicely.");
				nmeaClient.stopDataRead();
			}
		});

		nmeaClient.initClient();
		nmeaClient.setReader(new LSM303Reader(nmeaClient.getListeners()));
		nmeaClient.startWorking();
	}
}