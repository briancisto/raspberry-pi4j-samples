package context;

import java.util.logging.Level;
import nmea.mux.context.Context;
import nmea.parser.Angle;
import nmea.parser.Angle180;
import nmea.parser.Angle180EW;
import nmea.parser.Angle360;
import nmea.parser.ApparentWind;
import nmea.parser.Current;
import nmea.parser.Depth;
import nmea.parser.Distance;
import nmea.parser.GeoPos;
import nmea.parser.NMEADoubleValueHolder;
import nmea.parser.OverGround;
import nmea.parser.Pressure;
import nmea.parser.RMB;
import nmea.parser.RMC;
import nmea.parser.SVData;
import nmea.parser.SolarDate;
import nmea.parser.Speed;
import nmea.parser.StringGenerator;
import nmea.parser.StringParsers;
import nmea.parser.Temperature;
import nmea.parser.TrueWind;
import nmea.parser.UTC;
import nmea.parser.UTCDate;
import nmea.parser.UTCTime;
import nmea.parser.Wind;
import nmea.utils.NMEAUtils;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static nmea.utils.NMEAUtils.longitudeToTime;

public class NMEADataCache
				extends HashMap<String, Object>
				implements Serializable {

	public static final String LAST_NMEA_SENTENCE = "NMEA";

	public static final String SOG = "SOG";
	public static final String POSITION = "Position";
	public static final String GPS_DATE_TIME = "GPS Date & Time";
	public static final String GPS_TIME = "GPS Time";
	public static final String GPS_SOLAR_TIME = "Solar Time";
	public static final String COG = "COG";
	public static final String DECLINATION = "D";
	public static final String BSP = "BSP";
	public static final String LOG = "Log";
	public static final String DAILY_LOG = "Daily";
	public static final String WATER_TEMP = "Water Temperature";
	public static final String AIR_TEMP = "Air Temperature";
	public static final String BARO_PRESS = "Barometric Pressure";
	public static final String RELATIVE_HUMIDITY = "Relative Humidity";
	public static final String AWA = "AWA";
	public static final String AWS = "AWS";
	public static final String HDG_COMPASS = "HDG c.";
	public static final String HDG_MAG = "HDG mag.";
	public static final String HDG_TRUE = "HDG true";
	public static final String DEVIATION = "d";
	public static final String VARIATION = "W";
	public static final String TWA = "TWA";
	public static final String TWS = "TWS";
	public static final String TWD = "TWD";
	public static final String CSP = "CSP";
	public static final String CDR = "CDR";
	public static final String XTE = "XTE";
	public static final String FROM_WP = "From Waypoint";
	public static final String TO_WP = "To Waypoint";
	public static final String WP_POS = "WayPoint pos";
	public static final String DBT = "Depth";
	public static final String D2WP = "Distance to WP";
	public static final String B2WP = "Bearing to WP";
	public static final String S2WP = "Speed to WP";
	public static final String S2STEER = "Steer";
	public static final String LEEWAY = "Leeway";
	public static final String CMG = "CMG";
	public static final String SAT_IN_VIEW = "Satellites in view";

	public static final String BATTERY = "Battery Voltage";
	public static final String CALCULATED_CURRENT = "Current calculated with damping";
	public static final String VDR_CURRENT = "Set and Drift";

	public static final String BSP_FACTOR = "BSP Factor";
	public static final String AWS_FACTOR = "AWS Factor";
	public static final String AWA_OFFSET = "AWA Offset";
	public static final String HDG_OFFSET = "HDG Offset";
	public static final String MAX_LEEWAY = "Max Leeway";

	public static final String DEVIATION_FILE = "Deviation file name";
	public static final String DEVIATION_DATA = "Deviation data";
	public static final String DEFAULT_DECLINATION = "Default Declination";
	public static final String DAMPING = "Damping";

	public static final String TIME_RUNNING = "Time Running";

	public static final String VMG_ON_WIND = "VMG on Wind";
	public static final String VMG_ON_WP = "VMG to Waypoint";

	public static final String ALTITUDE = "Altitude";

	// Damping ArrayList's
	private transient int dampingSize = 1;

	private transient static List<String> NOT_TO_RESET = Arrays.asList(
					BSP_FACTOR,
					AWS_FACTOR,
					AWA_OFFSET,
					HDG_OFFSET,
					MAX_LEEWAY,
					DEFAULT_DECLINATION,
					DEVIATION_FILE,
					DEVIATION_DATA,
					DAMPING,
					CALCULATED_CURRENT);

	private transient HashMap<String, List<Object>> dampingMap = new HashMap<String, List<Object>>();

	private transient long started = 0L;

	private transient NMEADataCache instance = this;

	public NMEADataCache() {
		super();
		started = System.currentTimeMillis();
		if (System.getProperty("verbose", "false").equals("true")) {
			System.out.println("+=================================+");
			System.out.println("| Instantiating an NMEADataCache. |");
			System.out.println("+=================================+");
		}
		init();
	}

	private void init() {
		dampingMap.put(BSP, new ArrayList<Object>());
		dampingMap.put(HDG_TRUE, new ArrayList<Object>());
		dampingMap.put(AWA, new ArrayList<Object>());
		dampingMap.put(AWS, new ArrayList<Object>());
		dampingMap.put(TWA, new ArrayList<Object>());
		dampingMap.put(TWS, new ArrayList<Object>());
		dampingMap.put(TWD, new ArrayList<Object>());
		dampingMap.put(CSP, new ArrayList<Object>());
		dampingMap.put(CDR, new ArrayList<Object>());
		dampingMap.put(COG, new ArrayList<Object>());
		dampingMap.put(SOG, new ArrayList<Object>());
		dampingMap.put(LEEWAY, new ArrayList<Object>());

		// Initialization
		this.put(CALCULATED_CURRENT, new HashMap<Long, CurrentDefinition>());
	}

	public void reset() {
//	synchronized (this) {
			this.keySet()
							.stream()
							.filter(k -> !NOT_TO_RESET.contains(k))
							.forEach(k -> this.put(k, null));
			Map<Long, NMEADataCache.CurrentDefinition> currentMap = (Map<Long, NMEADataCache.CurrentDefinition>)this.get(NMEADataCache.CALCULATED_CURRENT);
			if (currentMap != null) {
				currentMap.keySet().stream().forEach(tbl -> currentMap.put(tbl, null));
			}
			init();
//	}
	}

	@Override
	public /*synchronized*/ Object put(String key, Object value) {
		Object o = null;
		synchronized (this) {
			o = super.put(key, value);
		}
		if (dampingSize > 1 && dampingMap.containsKey(key)) {
			List<Object> ald = dampingMap.get(key);
			ald.add(value);
			while (ald.size() > dampingSize)
				ald.remove(0);
		}
		return o;
	}

	public void parseAndFeed(String nmeaSentence) {
		if (StringParsers.validCheckSum(nmeaSentence)) {

			this.put(LAST_NMEA_SENTENCE, nmeaSentence);

			String id = StringParsers.getSentenceID(nmeaSentence);
			switch (id) {
				case "GGA":
					List<Object> gga = StringParsers.parseGGA(nmeaSentence);
					GeoPos ggaPos = (GeoPos)gga.get(StringParsers.GGA_POS_IDX);
					this.put(POSITION, ggaPos);
					UTC ggaDate = (UTC)gga.get(StringParsers.GGA_UTC_IDX);
					if (ggaDate != null) {
						this.put(GPS_DATE_TIME, new UTCDate(ggaDate.getDate()));
					}
			//	int ggaNbSat = (Integer)gga.get(StringParsers.GGA_NBSAT_IDX);
					double ggaAlt = (Double)gga.get(StringParsers.GGA_ALT_IDX);
					this.put(ALTITUDE, ggaAlt);
					break;
				case "RMC":
					RMC rmc = StringParsers.parseRMC(nmeaSentence);
					if (rmc != null) {
						this.put(POSITION, rmc.getGp());
						this.put(COG, new Angle360(rmc.getCog()));
						this.put(SOG, new Speed(rmc.getSog()));
						this.put(DECLINATION, new Angle180EW(rmc.getDeclination()));
						if (rmc.getRmcDate() != null) {
							this.put(GPS_DATE_TIME, new UTCDate(rmc.getRmcDate()));
						}
						if (rmc.getRmcTime() != null) {
							this.put(GPS_TIME, new UTCTime(rmc.getRmcTime()));
						}
						if ((rmc.getRmcDate() != null || rmc.getRmcTime() != null) && rmc.getGp() != null)
						{
							long solarTime = -1L;
							if (rmc.getRmcDate() != null)
								solarTime = rmc.getRmcDate().getTime() + longitudeToTime(rmc.getGp().lng);
							else
								solarTime = rmc.getRmcTime().getTime() + longitudeToTime(rmc.getGp().lng);
							Date solarDate = new Date(solarTime);
							this.put(GPS_SOLAR_TIME, new SolarDate(solarDate));
						}
					}
					break;
				case "ZDA":
					UTCDate utc = StringParsers.parseZDA(nmeaSentence);
					if (utc != null) {
						this.put(GPS_DATE_TIME, utc);
						this.put(GPS_TIME, new UTCTime(utc.getValue()));

						GeoPos pos = (GeoPos)this.get(POSITION);
						if (pos != null) {
							long solarTime = utc.getValue().getTime() + longitudeToTime(pos.lng);
							Date solarDate = new Date(solarTime);
							this.put(GPS_SOLAR_TIME, new SolarDate(solarDate));
						}
					}
					break;
				case "VHW": // Water Speed and Heading
					double[] vhw = StringParsers.parseVHW(nmeaSentence);
					if (vhw == null)
						return;
					double bsp = vhw[StringParsers.BSP_in_VHW];
					double hdm = vhw[StringParsers.HDM_in_VHW];
					if (bsp != -Double.MAX_VALUE) {
						this.put(BSP, new Speed(bsp));
					}
					this.put(HDG_COMPASS, new Angle360(hdm /* - dec */));
					break;
				case "VLW": // Log
					double[] d = StringParsers.parseVLW(nmeaSentence);
					HashMap<String, Object> map = new HashMap<String, Object>(2);
					this.put(LOG      , new Distance(d[StringParsers.LOG_in_VLW]));
					this.put(DAILY_LOG, new Distance(d[StringParsers.DAILYLOG_in_VLW]));
					break;
				case "MTW": // Water Temperature
					double wt = StringParsers.parseMTW(nmeaSentence);
					this.put(WATER_TEMP, new Temperature(wt));
					break;
				case "MTA": // Air Temperature
					double at = StringParsers.parseMTA(nmeaSentence);
					this.put(AIR_TEMP, new Temperature(at));
					break;
				case "MMB": // Barometric Pressure
					double p = StringParsers.parseMMB(nmeaSentence); // in mb
					this.put(BARO_PRESS, new Pressure(p));
					break;
				case "MWV": // Apparent Wind Speed and Direction
					Wind wind = StringParsers.parseMWV(nmeaSentence);
					if (wind != null && wind instanceof ApparentWind) { // TODO: TrueWind not used for now
						this.put(AWS, new Speed(wind.speed));
						int awa = wind.angle;
						if (awa > 180)
							awa -= 360;
						this.put(AWA, new Angle180(awa));
					}
					break;
				case "VDR":
					Current current = StringParsers.parseVDR(nmeaSentence);
					this.put(NMEADataCache.VDR_CURRENT, current);
					break;
				case "VWR": // Apparent Wind Speed and Direction (2)
					Wind aWind = StringParsers.parseVWR(nmeaSentence);
					if (aWind != null) {
						this.put(AWS, new Speed(aWind.speed));
						int awa = aWind.angle;
						if (awa > 180)
							awa -= 360;
						this.put(AWA, new Angle180(awa));
					}
					break;
				case "VTG": // Speed and Course over Ground
					OverGround og = StringParsers.parseVTG(nmeaSentence);
					if (og != null) {
						this.put(COG, new Angle360(og.getCourse()));
						this.put(SOG, new Speed(og.getSpeed()));
					}
					break;
				case "GLL": // Lat & Long, UTC (No date, just time)
						Object[] obj = StringParsers.parseGLL(nmeaSentence);
						if (obj != null) {
							GeoPos pos = (GeoPos)obj[StringParsers.GP_in_GLL];
							if (pos != null) {
								this.put(POSITION, pos);
							}
							Date date = (Date)obj[StringParsers.DATE_in_GLL];
							if (date != null) {
								this.put(GPS_TIME, new UTCTime(date));
								long solarTime = date.getTime() + longitudeToTime(pos.lng);
								Date solarDate = new Date(solarTime);
								this.put(GPS_SOLAR_TIME, new SolarDate(solarDate));
							}
						}
					break;
				case "HDM": // Heading, magnetic
					int hdg = StringParsers.parseHDM(nmeaSentence);
					this.put(HDG_COMPASS, new Angle360(hdg));
					break;
				case "HDT": // Heading, true
					this.put(NMEADataCache.HDG_TRUE, new Angle360(StringParsers.parseHDT(nmeaSentence)));
					break;
				case "HDG": // Heading
					double[] hdgData = StringParsers.parseHDG(nmeaSentence);
					int heading = (int)hdgData[StringParsers.HDG_in_HDG];
					double dev = hdgData[StringParsers.DEV_in_HDG];
					double var = hdgData[StringParsers.VAR_in_HDG];
					if (dev == -Double.MAX_VALUE && var == -Double.MAX_VALUE) {
						this.put(HDG_COMPASS, new Angle360(heading));
					} else {
						double dec = 0d;
						if (dev != -Double.MAX_VALUE)
							dec = dev;
						else
							dec = var;
						this.put(DECLINATION, new Angle180EW(dec));
						this.put(HDG_COMPASS, new Angle360(heading /* - dec */));
					}
					break;
				case "RMB":
					RMB rmb = StringParsers.parseRMB(nmeaSentence);
					if (rmb != null) {
						this.put(XTE,     new Distance(rmb.getXte()));
						this.put(WP_POS,  rmb.getDest());
						this.put(FROM_WP, rmb.getOwpid());
						this.put(TO_WP,   rmb.getDwpid());
						this.put(D2WP,    new Distance(rmb.getRtd()));
						this.put(B2WP,    new Angle360(rmb.getBtd()));
						this.put(S2WP,    new Speed(rmb.getDcv()));
						this.put(S2STEER, rmb.getDts());
					}
					break;
				case "DBT": // Depth
					float fb = StringParsers.parseDBT(nmeaSentence, StringParsers.DEPTH_IN_METERS);
					this.put(DBT, new Depth(fb));
					break;
				case "DPT": // Depth
					float fp = StringParsers.parseDPT(nmeaSentence, StringParsers.DEPTH_IN_METERS);
					this.put(DBT, new Depth(fp));
					break;
				case "GSV": // Satellites in view
					Map<Integer, SVData> satmap = StringParsers.parseGSV(nmeaSentence);
					if (satmap != null) {
						this.put(SAT_IN_VIEW, satmap);
					}
					break;
				case "MDA": // Meteorological composite (Humidity, among others)
					StringParsers.MDA mda = StringParsers.parseMDA(nmeaSentence);
					if (mda.airT != null)
						this.put(NMEADataCache.AIR_TEMP, new Temperature(mda.airT));
					if (mda.waterT != null)
						this.put(NMEADataCache.WATER_TEMP, new Temperature(mda.waterT));
					if (mda.pressBar != null)
						this.put(NMEADataCache.BARO_PRESS, new Pressure(mda.pressBar * 1_000));
					if (mda.relHum != null)
						this.put(NMEADataCache.RELATIVE_HUMIDITY, mda.relHum);
					// TODO: More MDA data...
					break;
				case "XTE": // Cross Track Error
					// TODO: Implement
					break;
				case "XDR": // Transducer measurement
					List<StringGenerator.XDRElement> xdr = StringParsers.parseXDR(nmeaSentence);
					if (xdr != null) {
						for (StringGenerator.XDRElement xe : xdr) {
							StringGenerator.XDRTypes type = xe.getTypeNunit();
							double val = xe.getValue();
							if (type.equals(StringGenerator.XDRTypes.HUMIDITY)) {
								this.put(RELATIVE_HUMIDITY, val);
							} else if (type.equals(StringGenerator.XDRTypes.PRESSURE_B)) {
								this.put(BARO_PRESS, new Pressure(val * 1_000));
							} else if (type.equals(StringGenerator.XDRTypes.VOLTAGE)) {
								this.put(BATTERY, new Float(val));
							} else {
								if ("true".equals(System.getProperty("verbose", "false")))
									System.out.println("Unmanaged XDR Type:" + type.toString());
							}
						}
					}
					break;
				case "MWD": // Wind Speed and Direction
					Wind mwdWind = StringParsers.parseMWD(nmeaSentence);
					if (mwdWind != null && mwdWind instanceof TrueWind) {
						this.put(TWS, new Speed(mwdWind.speed));
						this.put(TWD, new Angle360(mwdWind.angle));
					}
					break;
				case "VWT": // True Wind Speed and Angle (deprecated, use MWV)
					Wind trueWind = StringParsers.parseVWT(nmeaSentence);
					if (trueWind != null) {
						this.put(TWS, new Speed(trueWind.speed));
						this.put(TWA, new Angle180(trueWind.angle));
						Angle360 trueHeading = (Angle360)this.get(HDG_TRUE);
						if (trueHeading != null) {
							double twd = trueHeading.getValue() + trueWind.angle;
							System.out.println("TWD: " + twd); // TODO: Implement put(TWD, new Angle360(twd))
						}
					}
					break;
				case "BAT":     // Battery Voltage. Not Standard, from the Raspberry PI. There is an XDR Voltage...
					float volt = StringParsers.parseBAT(nmeaSentence);
					if (volt > -1) {
						this.put(BATTERY, new Float(volt));
					}
					break;
				case "STD":     // Cache age. Not Standard. From Original cache
					long age = StringParsers.parseSTD(nmeaSentence);
					if (age > -1) {
						this.put(TIME_RUNNING, new Long(age));
					}
					break;
				default:
					if (System.getProperty("verbose", "false").equals("true")) {
						System.out.println(String.format("NMEA Sentence [%s] not managed by parseAndFeed.", id));
					}
					break;
			}
		}
	}

	/**
	 * @param key identifies the data to get
	 * @return Damped Data, by default
	 */
	@Override
	public /*synchronized*/ Object get(Object key) {
		return get(key, true);
	}

	public /*synchronized*/ Object get(Object key, boolean useDamping) {
		Object ret = null;
		try {
			//  System.out.println("Damping = " + dampingSize);
			if (useDamping && dampingSize > 1 && dampingMap != null && dampingMap.containsKey(key)) {
				Class cl = null;
				List<?> ald = dampingMap.get(key);
				double sum = 0d;
				double sumCos = 0d,
								sumSin = 0d;

				for (Object v : ald) {
					if (cl == null)
						cl = v.getClass();
					if (v instanceof Double)
						sum += ((Double) v).doubleValue();
					else if (v instanceof NMEADoubleValueHolder) {
						// Debug
						if (false && key.equals(TWD))
							System.out.print(((NMEADoubleValueHolder) v).getDoubleValue() + ";");

						if (v instanceof Angle) { // Angle360 || v instanceof Angle180 || v instanceof Angle180EW || v instanceof Angle180LR)
							double val = ((NMEADoubleValueHolder) v).getDoubleValue();
							sumCos += (Math.cos(Math.toRadians(val)));
							sumSin += (Math.sin(Math.toRadians(val)));
						} else
							sum += ((NMEADoubleValueHolder) v).getDoubleValue();
					} else
						System.out.println("What'zat:" + v.getClass().getName());
				}
				try {
					if (ald.size() != 0) { // Average here
						sum /= ald.size();
						sumCos /= ald.size();
						sumSin /= ald.size();
					}
					if (cl != null) {
						if (cl.equals(Double.class)) {
							ret = new Double(sum);
						} else {
							ret = Class.forName(cl.getName()).newInstance();
							if (ret instanceof Angle) { // Angle360 || ret instanceof Angle180 || ret instanceof Angle180EW || ret instanceof Angle180LR)
								double a = Math.toDegrees(Math.acos(sumCos));
								if (sumSin < 0)
									a = 360d - a;
								sum = a;
							}
							((NMEADoubleValueHolder) ret).setDoubleValue(sum);
						}
					} else
						ret = super.get(key);
				} catch (Exception ex) {
					Context.getInstance().getLogger().log(Level.INFO, String.format("For key", key), ex);
				}
			} else {
				ret = super.get(key);
//				if (ret == null) {
//					long age = System.currentTimeMillis() - started;
//					ret = new Long(age);
//				}
			}
		} catch (ConcurrentModificationException cme) {
			Context.getInstance().getLogger().log(Level.INFO, String.format("Conflict for key [%s]", key), cme);
		}
		return ret;
	}

	public void setDampingSize(int dampingSize) {
		System.out.println("Setting Damping to " + dampingSize);
		this.dampingSize = dampingSize;
	}

	public int getDampingSize() {
		return dampingSize;
	}

	public void resetDampingBuffers() {
		Set<String> keys = dampingMap.keySet();
		for (String k : keys)
			dampingMap.get(k).clear();
	}

	public static class CurrentDefinition implements Serializable {
		private long bufferLength; // in ms
		private Speed speed;
		private Angle360 direction;
		private int nbPoints = 0;
		private String oldest = "";
		private String latest = "";
		private long len = 0L; // Len in ms

		public long getBufferLength() {
			return bufferLength;
		}

		public Speed getSpeed() {
			return speed;
		}

		public Angle360 getDirection() {
			return direction;
		}


		public CurrentDefinition(long bl, Speed sp, Angle360 dir, int nbp, String old, String last, long len) {
			this.bufferLength = bl;
			this.speed = sp;
			this.direction = dir;
			this.nbPoints = nbp;
			this.oldest = old;
			this.latest = last;
			this.len = len;
		}
	}
}
