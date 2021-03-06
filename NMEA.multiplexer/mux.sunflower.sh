#!/usr/bin/env bash
#
MUX_PROP_FILE=nmea.mux.sun.flower.properties
#
echo Using properties file $MUX_PROP_FILE
#
JAVA_OPTIONS=
# JAVA_OPTIONS="$JAVA_OPTIONS -Djava.library.path=./libs"       # for Mac
JAVA_OPTIONS="$JAVA_OPTIONS -Djava.library.path=/usr/lib/jni" # for Raspberry PI
#
# JAVA_OPTIONS="$JAVA_OPTIONS -Dserial.data.verbose=false"
# JAVA_OPTIONS="$JAVA_OPTIONS -Dtcp.data.verbose=false"
# JAVA_OPTIONS="$JAVA_OPTIONS -Dfile.data.verbose=false"
# JAVA_OPTIONS="$JAVA_OPTIONS -Dws.data.verbose=false"
# JAVA_OPTIONS="$JAVA_OPTIONS -Dhtu21df.data.verbose=false"
# JAVA_OPTIONS="$JAVA_OPTIONS -Dbme280.data.verbose=false"
# JAVA_OPTIONS="$JAVA_OPTIONS -Drnd.data.verbose=false"
# JAVA_OPTIONS="$JAVA_OPTIONS -Dzda.data.verbose=true"
# JAVA_OPTIONS="$JAVA_OPTIONS -Dhttp.verbose=true"
# JAVA_OPTIONS="$JAVA_OPTIONS -Dmux.data.verbose=false"
# JAVA_OPTIONS="$JAVA_OPTIONS -Dverbose=false"
#
JAVA_OPTIONS="$JAVA_OPTIONS -Dmux.properties=$MUX_PROP_FILE"
#
# JAVA_OPTIONS="$JAVA_OPTONS -Dpi4j.debug -Dpi4j.linking=dynamic"
#
CP=./build/libs/NMEA.multiplexer-1.0-all.jar
CP=$CP:../SunFlower/build/libs/SunFlower-1.0-all.jar
CP=$CP:../GPS.sun.servo/build/libs/GPS.sun.servo-1.0.jar
# CP=$CP:./libs/RXTXcomm.jar          # for Mac
CP=$CP:/usr/share/java/RXTXcomm.jar # For Raspberry PI
#
# For JFR
JFR_FLAGS=
# JFR_FLAGS="-XX:+UnlockCommercialFeatures -XX:+FlightRecorder -XX:StartFlightRecording=duration=10m,filename=nmea.jfr"
# For remote debugging
REMOTE_DEBUG_FLAGS=
# REMOTE_DEBUG_FLAGS="$REMOTE_DEBUG_FLAGS -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005"
#
LOGGING_FLAG=
LOGGING_FLAG=-Djava.util.logging.config.file=./logging.properties
# use sudo on Raspberry PI
sudo java $JAVA_OPTIONS $LOGGING_FLAG $JFR_FLAGS $REMOTE_DEBUG_FLAGS -cp $CP nmea.mux.GenericNMEAMultiplexer
#
