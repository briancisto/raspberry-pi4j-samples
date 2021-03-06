package nmea.mux;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import context.ApplicationContext;
import context.NMEADataCache;
import gnu.io.CommPortIdentifier;
import http.HTTPServer;
import http.HTTPServer.Request;
import http.HTTPServer.Response;
import http.RESTProcessorUtil;
import java.io.FileReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.stream.Collectors;
import nmea.api.Multiplexer;
import nmea.api.NMEAClient;
import nmea.api.NMEAReader;
import nmea.computers.Computer;
import nmea.computers.ExtraDataComputer;
import nmea.consumers.client.BME280Client;
import nmea.consumers.client.BMP180Client;
import nmea.consumers.client.DataFileClient;
import nmea.consumers.client.HTU21DFClient;
import nmea.consumers.client.LSM303Client;
import nmea.consumers.client.RandomClient;
import nmea.consumers.client.SerialClient;
import nmea.consumers.client.TCPClient;
import nmea.consumers.client.WebSocketClient;
import nmea.consumers.client.ZDAClient;
import nmea.consumers.reader.BME280Reader;
import nmea.consumers.reader.BMP180Reader;
import nmea.consumers.reader.DataFileReader;
import nmea.consumers.reader.HTU21DFReader;
import nmea.consumers.reader.LSM303Reader;
import nmea.consumers.reader.RandomReader;
import nmea.consumers.reader.SerialReader;
import nmea.consumers.reader.TCPReader;
import nmea.consumers.reader.WebSocketReader;
import nmea.consumers.reader.ZDAReader;
import nmea.forwarders.ConsoleWriter;
import nmea.forwarders.DataFileWriter;
import nmea.forwarders.Forwarder;
import nmea.forwarders.GPSdServer;
import nmea.forwarders.SerialWriter;
import nmea.forwarders.TCPServer;
import nmea.forwarders.WebSocketProcessor;
import nmea.forwarders.WebSocketWriter;
import nmea.forwarders.rmi.RMIServer;
import nmea.mux.context.Context;
import nmea.mux.context.Context.StringAndTimeStamp;
import nmea.utils.NMEAUtils;

/**
 * This class defines the REST operations supported by the HTTP Server.
 *
 * This list is defined in the <code>List&lt;Operation&gt;</code> named <code>operations</code>.
 * <br>
 * The Multiplexer will use the {@link #processRequest(Request, Response)} method of this class to
 * have the required requests processed.
 */
public class RESTImplementation {

	private List<NMEAClient> nmeaDataClients;
	private List<Forwarder> nmeaDataForwarders;
	private List<Computer> nmeaDataComputers;
	private Multiplexer mux;

	public RESTImplementation(List<NMEAClient> nmeaDataClients,
	                          List<Forwarder> nmeaDataForwarders,
	                          List<Computer> nmeaDataComputers,
	                          Multiplexer mux) {
		this.nmeaDataClients = nmeaDataClients;
		this.nmeaDataForwarders = nmeaDataForwarders;
		this.nmeaDataComputers = nmeaDataComputers;
		this.mux = mux;

		// Check duplicates in operation list. Barfs if duplicate is found.
		for (int i = 0; i < operations.size(); i++) {
			for (int j = i + 1; j < operations.size(); j++) {
				if (operations.get(i).getVerb().equals(operations.get(j).getVerb()) &&
								RESTProcessorUtil.pathsAreIndentical(operations.get(i).getPath(), operations.get(j).getPath())) {
					throw new RuntimeException(String.format("Duplicate entry in operations list %s %s", operations.get(i).getVerb(), operations.get(i).getPath()));
				}
			}
		}
	}

	private static class Operation {
		String verb;
		String path;
		String description;
		Function<Request, Response> fn;

		public Operation(String verb, String path, Function<HTTPServer.Request, HTTPServer.Response> fn, String description) {
			this.verb = verb;
			this.path = path;
			this.description = description;
			this.fn = fn;
		}

		String getVerb() {
			return verb;
		}

		String getPath() {
			return path;
		}

		String getDescription() {
			return description;
		}

		Function<HTTPServer.Request, HTTPServer.Response> getFn() {
			return fn;
		}
	}

	/**
	 * Define all the REST operations to be managed
	 * by the HTTP server.
	 * <p>
	 * Frame path parameters with curly braces.
	 * <p>
	 * See {@link #processRequest(HTTPServer.Request, HTTPServer.Response)}
	 * See {@link HTTPServer}
	 */
	private List<Operation> operations = Arrays.asList(
					new Operation(
									"GET",
									"/oplist",
									this::getOperationList,
									"List of all available operations."),
					new Operation(
									"GET",
									"/serial-ports",
									this::getSerialPorts,
									"Get the list of the available serial ports."),
					new Operation(
									"GET",
									"/channels",
									this::getChannels,
									"Get the list of the input channels"),
					new Operation(
									"GET",
									"/forwarders",
									this::getForwarders,
									"Get the list of the output channels"),
					new Operation(
									"GET",
									"/computers",
									this::getComputers,
									"Get the list of the computers"),
					new Operation(
									"DELETE",
									"/forwarders/{id}",
									this::deleteForwarder,
									"Delete an output channel"),
					new Operation(
									"DELETE",
									"/channels/{id}",
									this::deleteChannel,
									"Delete an input channel"),
					new Operation(
									"DELETE",
									"/computers/{id}",
									this::deleteComputer,
									"Delete a computer"),
					new Operation(
									"POST",
									"/forwarders",
									this::postForwarder,
									"Creates an output channel"),
					new Operation(
									"POST",
									"/channels",
									this::postChannel,
									"Creates an input channel"),
					new Operation(
									"POST",
									"/computers",
									this::postComputer,
									"Creates computer"),
					new Operation(
									"PUT",
									"/channels/{id}",
									this::putChannel,
									"Update channel"),
					new Operation(
									"PUT",
									"/forwarders/{id}",
									this::putForwarder,
									"Update forwarder"),
					new Operation(
									"PUT",
									"/computers/{id}",
									this::putComputer,
									"Update computer"),
					new Operation(
									"PUT",
									"/mux-verbose/{state}",
									this::putMuxVerbose,
									"Update Multiplexer verbose"),
					new Operation(
									"GET",
									"/cache",
									this::getCache,
									"Get ALL the data in the cache"),
					new Operation(
									"DELETE",
									"/cache",
									this::resetCache,
									"Reset the cache"),
					new Operation(
									"GET",
									"/nmea-volume",
									this::getNMEAVolumeStatus,
									"Get the time elapsed and the NMEA volume managed so far"),
					new Operation(
									"GET",
									"/last-sentence",
									this::getLastNMEASentence,
									"Get the last available inbound sentence"));

	/**
	 * This is the method to invoke to have a REST request processed as defined above.
	 *
	 * @param request as it comes from the client
	 * @param defaultResponse with the expected 'happy' code.
	 * @return the actual result.
	 */
	public HTTPServer.Response processRequest(HTTPServer.Request request, HTTPServer.Response defaultResponse) {
		Optional<Operation> opOp = operations
						.stream()
						.filter(op -> op.getVerb().equals(request.getVerb()) && RESTProcessorUtil.pathMatches(op.getPath(), request.getPath()))
						.findFirst();
		if (opOp.isPresent()) {
			Operation op = opOp.get();
			request.setRequestPattern(op.getPath()); // To get the prms later on.
			HTTPServer.Response processed = op.getFn().apply(request); // Execute here.
			return processed;
		}
		return defaultResponse;
	}

	private HTTPServer.Response getSerialPorts(HTTPServer.Request request) {
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.STATUS_OK);

		try {
			List<String> portList = getSerialPortList();
			Object[] portArray = portList.toArray(new Object[portList.size()]);
			String content = new Gson().toJson(portArray).toString();
			RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
			response.setPayload(content.getBytes());
		} catch (Error error) {
			response.setStatus(HTTPServer.Response.BAD_REQUEST);
			RESTProcessorUtil.addErrorMessageToResponse(response, error.toString());
		}
		return response;
	}

	private HTTPServer.Response getChannels(HTTPServer.Request request) {
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.STATUS_OK);

		List<Object> channelList = getInputChannelList();
		Object[] channelArray = channelList.stream()
						.collect(Collectors.toList())
						.toArray(new Object[channelList.size()]);

		String content = new Gson().toJson(channelArray);
		RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
		response.setPayload(content.getBytes());

		return response;
	}

	private HTTPServer.Response getForwarders(HTTPServer.Request request) {
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.STATUS_OK);
		List<Object> forwarderList = getForwarderList();
		Object[] forwarderArray = forwarderList.stream()
						.collect(Collectors.toList())
						.toArray(new Object[forwarderList.size()]);

		String content = new Gson().toJson(forwarderArray);
		RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
		response.setPayload(content.getBytes());

		return response;
	}

	private HTTPServer.Response getComputers(HTTPServer.Request request) {
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.STATUS_OK);
		List<Object> computerList = getComputerList();
		Object[] forwarderArray = computerList.stream()
						.collect(Collectors.toList())
						.toArray(new Object[computerList.size()]);

		String content = new Gson().toJson(forwarderArray);
		RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
		response.setPayload(content.getBytes());

		return response;
	}

	private HTTPServer.Response deleteForwarder(HTTPServer.Request request) {
		Optional<Forwarder> opFwd = null;
		Gson gson = null;
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), 204);
		List<String> prmValues = RESTProcessorUtil.getPrmValues(request.getRequestPattern(), request.getPath());
		if (prmValues.size() == 1) {
			String id = prmValues.get(0);
			switch (id) {
				case "console":
					opFwd = nmeaDataForwarders.stream()
									.filter(fwd -> fwd instanceof ConsoleWriter)
									.findFirst();
					response = removeForwarderIfPresent(request, opFwd);
					break;
				case "serial":
					gson = new GsonBuilder().create();
					if (request.getContent() != null) {
						StringReader stringReader = new StringReader(new String(request.getContent()));
						SerialWriter.SerialBean serialBean = gson.fromJson(stringReader, SerialWriter.SerialBean.class);
						opFwd = nmeaDataForwarders.stream()
										.filter(fwd -> fwd instanceof SerialWriter &&
														((SerialWriter) fwd).getPort().equals(serialBean.getPort()))
										.findFirst();
						response = removeForwarderIfPresent(request, opFwd);
					} else {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "missing payload");
					}
					break;
				case "file":
					gson = new GsonBuilder().create();
					if (request.getContent() != null) {
						StringReader stringReader = new StringReader(new String(request.getContent()));
						DataFileWriter.DataFileBean dataFileBean = gson.fromJson(stringReader, DataFileWriter.DataFileBean.class);
						opFwd = nmeaDataForwarders.stream()
										.filter(fwd -> fwd instanceof DataFileWriter &&
														((DataFileWriter) fwd).getLog().equals(dataFileBean.getLog()))
										.findFirst();
						response = removeForwarderIfPresent(request, opFwd);
					} else {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "missing payload");
					}
					break;
				case "tcp":
					gson = new GsonBuilder().create();
					if (request.getContent() != null) {
						StringReader stringReader = new StringReader(new String(request.getContent()));
						TCPServer.TCPBean tcpBean = gson.fromJson(stringReader, TCPServer.TCPBean.class);
						opFwd = nmeaDataForwarders.stream()
										.filter(fwd -> fwd instanceof TCPServer &&
														((TCPServer) fwd).getTcpPort() == tcpBean.getPort())
										.findFirst();
						response = removeForwarderIfPresent(request, opFwd);
					} else {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "missing payload");
					}
					break;
				case "gpsd":
					gson = new GsonBuilder().create();
					if (request.getContent() != null) {
						StringReader stringReader = new StringReader(new String(request.getContent()));
						GPSdServer.GPSdBean gpsdBean = gson.fromJson(stringReader, GPSdServer.GPSdBean.class);
						opFwd = nmeaDataForwarders.stream()
										.filter(fwd -> fwd instanceof GPSdServer &&
														((GPSdServer) fwd).getTcpPort() == gpsdBean.getPort())
										.findFirst();
						response = removeForwarderIfPresent(request, opFwd);
					} else {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "missing payload");
					}
					break;
				case "rmi":
					gson = new GsonBuilder().create();
					if (request.getContent() != null) {
						StringReader stringReader = new StringReader(new String(request.getContent()));
						RMIServer.RMIBean rmiBean = gson.fromJson(stringReader, RMIServer.RMIBean.class);
						opFwd = nmeaDataForwarders.stream()
										.filter(fwd -> fwd instanceof RMIServer &&
														((RMIServer) fwd).getRegistryPort() == rmiBean.getPort())
										.findFirst();
						response = removeForwarderIfPresent(request, opFwd);
					} else {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "missing payload");
					}
					break;
				case "ws":
					gson = new GsonBuilder().create();
					if (request.getContent() != null) {
						StringReader stringReader = new StringReader(new String(request.getContent()));
						WebSocketWriter.WSBean wsBean = gson.fromJson(stringReader, WebSocketWriter.WSBean.class);
						opFwd = nmeaDataForwarders.stream()
										.filter(fwd -> fwd instanceof WebSocketWriter &&
														((WebSocketWriter) fwd).getWsUri().equals(wsBean.getWsUri()))
										.findFirst();
						response = removeForwarderIfPresent(request, opFwd);
					} else {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "missing payload");
					}
					break;
				case "wsp":
					gson = new GsonBuilder().create();
					if (request.getContent() != null) {
						StringReader stringReader = new StringReader(new String(request.getContent()));
						WebSocketProcessor.WSBean wsBean = gson.fromJson(stringReader, WebSocketProcessor.WSBean.class);
						opFwd = nmeaDataForwarders.stream()
										.filter(fwd -> fwd instanceof WebSocketProcessor &&
														((WebSocketProcessor) fwd).getWsUri().equals(wsBean.getWsUri()))
										.findFirst();
						response = removeForwarderIfPresent(request, opFwd);
					} else {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "missing payload");
					}
					break;
				case "udp":
					response.setStatus(HTTPServer.Response.NOT_IMPLEMENTED);
					break;
				default:
					if (request.getContent() != null) {
						StringReader stringReader = new StringReader(new String(request.getContent()));
						@SuppressWarnings("unchecked")
						Map<String, String> custom = (Map<String, String>)new Gson().fromJson(stringReader, Object.class);
						opFwd = nmeaDataForwarders.stream()
										.filter(fwd -> fwd.getClass().getName().equals(custom.get("cls")))
										.findFirst();
						response = removeForwarderIfPresent(request, opFwd);
					} else {
						response.setStatus(HTTPServer.Response.NOT_IMPLEMENTED);
					}
					break;
			}
		} else {
			response.setStatus(HTTPServer.Response.BAD_REQUEST);
			RESTProcessorUtil.addErrorMessageToResponse(response, "missing path parameter");
		}
		return response;
	}

	private HTTPServer.Response deleteChannel(HTTPServer.Request request) {
		Optional<NMEAClient> opClient = null;
		Gson gson = null;
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.NO_CONTENT);
		List<String> prmValues = RESTProcessorUtil.getPrmValues(request.getRequestPattern(), request.getPath());
		if (prmValues.size() == 1) {
			String id = prmValues.get(0);
			switch (id) {
				case "file":
					gson = new GsonBuilder().create();
					if (request.getContent() != null) {
						StringReader stringReader = new StringReader(new String(request.getContent()));
						DataFileClient.DataFileBean dataFileBean = gson.fromJson(stringReader, DataFileClient.DataFileBean.class);
						opClient = nmeaDataClients.stream()
										.filter(channel -> channel instanceof DataFileClient &&
														((DataFileClient.DataFileBean) ((DataFileClient) channel).getBean()).getFile().equals(dataFileBean.getFile()))
										.findFirst();
						response = removeChannelIfPresent(request, opClient);
					} else {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "missing payload");
					}
					break;
				case "serial":
					gson = new GsonBuilder().create();
					if (request.getContent() != null) {
						StringReader stringReader = new StringReader(new String(request.getContent()));
						SerialClient.SerialBean serialBean = gson.fromJson(stringReader, SerialClient.SerialBean.class);
						opClient = nmeaDataClients.stream()
										.filter(channel -> channel instanceof SerialClient &&
														((SerialClient.SerialBean) ((SerialClient) channel).getBean()).getPort().equals(serialBean.getPort())) // No need for BaudRate
										.findFirst();
						response = removeChannelIfPresent(request, opClient);
					} else {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "missing payload");
					}
					break;
				case "tcp":
					gson = new GsonBuilder().create();
					if (request.getContent() != null) {
						StringReader stringReader = new StringReader(new String(request.getContent()));
						TCPClient.TCPBean tcpBean = gson.fromJson(stringReader, TCPClient.TCPBean.class);
						opClient = nmeaDataClients.stream()
										.filter(channel -> channel instanceof TCPClient &&
														((TCPClient.TCPBean) ((TCPClient) channel).getBean()).getPort() == tcpBean.getPort())
										.findFirst();
						response = removeChannelIfPresent(request, opClient);
					} else {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "missing payload");
					}
					break;
				case "ws":
					gson = new GsonBuilder().create();
					if (request.getContent() != null) {
						StringReader stringReader = new StringReader(new String(request.getContent()));
						WebSocketClient.WSBean wsBean = gson.fromJson(stringReader, WebSocketClient.WSBean.class);
						opClient = nmeaDataClients.stream()
										.filter(channel -> channel instanceof WebSocketClient &&
														((WebSocketClient.WSBean) ((WebSocketClient) channel).getBean()).getWsUri().equals(wsBean.getWsUri()))
										.findFirst();
						response = removeChannelIfPresent(request, opClient);
					} else {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "missing payload");
					}
					break;
				case "bmp180":
					opClient = nmeaDataClients.stream()
									.filter(channel -> channel instanceof BMP180Client)
									.findFirst();
					response = removeChannelIfPresent(request, opClient);
					break;
				case "bme280":
					opClient = nmeaDataClients.stream()
									.filter(channel -> channel instanceof BME280Client)
									.findFirst();
					response = removeChannelIfPresent(request, opClient);
					break;
				case "lsm303":
					opClient = nmeaDataClients.stream()
									.filter(channel -> channel instanceof LSM303Client)
									.findFirst();
					response = removeChannelIfPresent(request, opClient);
					break;
				case "htu21df":
					opClient = nmeaDataClients.stream()
									.filter(channel -> channel instanceof HTU21DFClient)
									.findFirst();
					response = removeChannelIfPresent(request, opClient);
					break;
				case "rnd":
					opClient = nmeaDataClients.stream()
									.filter(channel -> channel instanceof RandomClient)
									.findFirst();
					response = removeChannelIfPresent(request, opClient);
					break;
				case "zda":
					opClient = nmeaDataClients.stream()
									.filter(channel -> channel instanceof ZDAClient)
									.findFirst();
					response = removeChannelIfPresent(request, opClient);
					break;
				default:
					if (request.getContent() != null) {
						StringReader stringReader = new StringReader(new String(request.getContent()));
						@SuppressWarnings("unchecked")
						Map<String, String> custom = (Map<String, String>)new Gson().fromJson(stringReader, Object.class);
						opClient = nmeaDataClients.stream()
										.filter(channel -> channel.getClass().getName().equals(custom.get("cls")))
										.findFirst();
						response = removeChannelIfPresent(request, opClient);
					} else {
						response.setStatus(HTTPServer.Response.NOT_IMPLEMENTED);
					}
					break;
			}
		} else {
			response.setStatus(HTTPServer.Response.BAD_REQUEST);
			RESTProcessorUtil.addErrorMessageToResponse(response, "missing path parameter");
		}
		return response;
	}

	private HTTPServer.Response deleteComputer(HTTPServer.Request request) {
		Optional<Computer> opComputer = null;
		Gson gson = null;
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.NO_CONTENT);
		List<String> prmValues = RESTProcessorUtil.getPrmValues(request.getRequestPattern(), request.getPath());
		if (prmValues.size() == 1) {
			String id = prmValues.get(0);
			switch (id) {
				case "tw-current":
					gson = new GsonBuilder().create();
					if (request.getContent() != null) {  // Really? Need that?
						opComputer = nmeaDataComputers.stream()
										.filter(cptr -> cptr instanceof ExtraDataComputer)
										.findFirst();
						response = removeComputerIfPresent(request, opComputer);
					} else {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "'tw-current' was not found");
					}
					break;
				default:
					if (request.getContent() != null) {
						StringReader stringReader = new StringReader(new String(request.getContent()));
						@SuppressWarnings("unchecked")
						Map<String, String> custom = (Map<String, String>)new Gson().fromJson(stringReader, Object.class);
						opComputer = nmeaDataComputers.stream()
										.filter(cptr -> cptr.getClass().getName().equals(custom.get("cls")))
										.findFirst();
						response = removeComputerIfPresent(request, opComputer);
					} else {
						response.setStatus(HTTPServer.Response.NOT_IMPLEMENTED);
					}
					break;
			}
		} else {
			response.setStatus(HTTPServer.Response.BAD_REQUEST);
			RESTProcessorUtil.addErrorMessageToResponse(response, "missing path parameter");
		}
		return response;
	}

	private HTTPServer.Response postForwarder(HTTPServer.Request request) {
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.STATUS_OK);
		Optional<Forwarder> opFwd = null;
		String type = "";
		if (request.getContent() == null || request.getContent().length == 0) {
			response.setStatus(HTTPServer.Response.BAD_REQUEST);
			RESTProcessorUtil.addErrorMessageToResponse(response, "missing payload");
			return response;
		} else {
			Object bean = new GsonBuilder().create().fromJson(new String(request.getContent()), Object.class);
			if (bean instanceof Map) {
				type = ((Map<String, String>) bean).get("type");
			}
		}
		switch (type) {
			case "console":
				// Check existence
				opFwd = nmeaDataForwarders.stream()
								.filter(fwd -> fwd instanceof ConsoleWriter)
								.findFirst();
				if (!opFwd.isPresent()) {
					try {
						Forwarder consoleForwarder = new ConsoleWriter();
						nmeaDataForwarders.add(consoleForwarder);
						response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.STATUS_OK);
						String content = new Gson().toJson(consoleForwarder.getBean());
						RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
						response.setPayload(content.getBytes());
					} catch (Exception ex) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
						ex.printStackTrace();
					}
				} else {
					// Already there
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, "'console' already exists");
				}
				break;
			case "serial":
				SerialWriter.SerialBean serialJson = new Gson().fromJson(new String(request.getContent()), SerialWriter.SerialBean.class);
				// Check if not there yet.
				opFwd = nmeaDataForwarders.stream()
								.filter(fwd -> fwd instanceof SerialWriter &&
												((SerialWriter) fwd).getPort() == serialJson.getPort())
								.findFirst();
				if (!opFwd.isPresent()) {
					try {
						Forwarder serialForwarder = new SerialWriter(serialJson.getPort(), serialJson.getBR());
						nmeaDataForwarders.add(serialForwarder);
						String content = new Gson().toJson(serialForwarder.getBean());
						RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
						response.setPayload(content.getBytes());
					} catch (Exception ex) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
						ex.printStackTrace();
					}
				} else {
					// Already there
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'serial' already exists");
				}
				break;
			case "tcp":
				TCPServer.TCPBean tcpJson = new Gson().fromJson(new String(request.getContent()), TCPServer.TCPBean.class);
				// Check if not there yet.
				opFwd = nmeaDataForwarders.stream()
								.filter(fwd -> fwd instanceof TCPServer &&
												((TCPServer) fwd).getTcpPort() == tcpJson.getPort())
								.findFirst();
				if (!opFwd.isPresent()) {
					try {
						Forwarder tcpForwarder = new TCPServer(tcpJson.getPort());
						nmeaDataForwarders.add(tcpForwarder);
						String content = new Gson().toJson(tcpForwarder.getBean());
						RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
						response.setPayload(content.getBytes());
					} catch (Exception ex) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
						ex.printStackTrace();
					}
				} else {
					// Already there
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'tcp' already exists");
				}
				break;
			case "gpsd":
				GPSdServer.GPSdBean gpsdJson = new Gson().fromJson(new String(request.getContent()), GPSdServer.GPSdBean.class);
				// Check if not there yet.
				opFwd = nmeaDataForwarders.stream()
								.filter(fwd -> fwd instanceof GPSdServer &&
												((GPSdServer) fwd).getTcpPort() == gpsdJson.getPort())
								.findFirst();
				if (!opFwd.isPresent()) {
					try {
						Forwarder gpsdForwarder = new GPSdServer(gpsdJson.getPort());
						nmeaDataForwarders.add(gpsdForwarder);
						String content = new Gson().toJson(gpsdForwarder.getBean());
						RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
						response.setPayload(content.getBytes());
					} catch (Exception ex) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
						ex.printStackTrace();
					}
				} else {
					// Already there
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'gpsd' already exists");
				}
				break;
			case "rmi":
				RMIServer.RMIBean rmiJson = new Gson().fromJson(new String(request.getContent()), RMIServer.RMIBean.class);
				// Check if not there yet.
				opFwd = nmeaDataForwarders.stream()
								.filter(fwd -> fwd instanceof RMIServer &&
												((RMIServer) fwd).getRegistryPort() == rmiJson.getPort())
								.findFirst();
				if (!opFwd.isPresent()) {
					try {
						Forwarder rmiForwarder = new RMIServer(rmiJson.getPort(), rmiJson.getBindingName());
						nmeaDataForwarders.add(rmiForwarder);
						String content = new Gson().toJson(rmiForwarder.getBean());
						RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
						response.setPayload(content.getBytes());
					} catch (Exception ex) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
						ex.printStackTrace();
					}
				} else {
					// Already there
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'rmi' already exists");
				}
				break;
			case "file":
				DataFileWriter.DataFileBean fileJson = new Gson().fromJson(new String(request.getContent()), DataFileWriter.DataFileBean.class);
				// Check if not there yet.
				opFwd = nmeaDataForwarders.stream()
								.filter(fwd -> fwd instanceof DataFileWriter &&
												((DataFileWriter) fwd).getLog().equals(fileJson.getLog()))
								.findFirst();
				if (!opFwd.isPresent()) {
					try {
						Forwarder fileForwarder = new DataFileWriter(fileJson.getLog(), fileJson.append());
						nmeaDataForwarders.add(fileForwarder);
						String content = new Gson().toJson(fileForwarder.getBean());
						RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
						response.setPayload(content.getBytes());
					} catch (Exception ex) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
						ex.printStackTrace();
					}
				} else {
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'file' alreacy exists");
				}
				break;
			case "ws":
				WebSocketWriter.WSBean wsJson = new Gson().fromJson(new String(request.getContent()), WebSocketWriter.WSBean.class);
				// Check if not there yet.
				opFwd = nmeaDataForwarders.stream()
								.filter(fwd -> fwd instanceof WebSocketWriter &&
												((WebSocketWriter) fwd).getWsUri() == wsJson.getWsUri())
								.findFirst();
				if (!opFwd.isPresent()) {
					try {
						Forwarder wsForwarder = new WebSocketWriter(wsJson.getWsUri());
						nmeaDataForwarders.add(wsForwarder);
						String content = new Gson().toJson(wsForwarder.getBean());
						RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
						response.setPayload(content.getBytes());
					} catch (Exception ex) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
						ex.printStackTrace();
					}
				} else {
					// Already there
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'ws' already exists");
				}
				break;
			case "wsp":
				WebSocketProcessor.WSBean wspJson = new Gson().fromJson(new String(request.getContent()), WebSocketProcessor.WSBean.class);
				// Check if not there yet.
				opFwd = nmeaDataForwarders.stream()
								.filter(fwd -> fwd instanceof WebSocketProcessor &&
												((WebSocketProcessor) fwd).getWsUri() == wspJson.getWsUri())
								.findFirst();
				if (!opFwd.isPresent()) {
					try {
						Forwarder wspForwarder = new WebSocketProcessor(wspJson.getWsUri());
						nmeaDataForwarders.add(wspForwarder);
						String content = new Gson().toJson(wspForwarder.getBean());
						RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
						response.setPayload(content.getBytes());
					} catch (Exception ex) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
						ex.printStackTrace();
					}
				} else {
					// Already there
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'wsp' already exists");
				}
				break;
			case "custom":
				String payload = new String(request.getContent());
				Object custom = new Gson().fromJson(payload, Object.class);
				if (custom instanceof Map) {
					@SuppressWarnings("unchecked")
					Map<String, String> map = (Map<String, String>)custom;
					String forwarderClass = map.get("forwarderClass").trim();
					String propFile = map.get("propFile");
					// Make sure client and reader are not null
					if (forwarderClass == null || forwarderClass.length() == 0) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "Require at least class name.");
						return response;
					}
					// Check Existence
					opFwd = nmeaDataForwarders.stream()
									.filter(fwd -> fwd.getClass().getName().equals(forwarderClass))
									.findFirst();
					if (!opFwd.isPresent()) {
						try {
							// Create dynamic forwarder
							Object dynamic = Class.forName(forwarderClass).newInstance();
							if (dynamic instanceof Forwarder) {
								Forwarder forwarder = (Forwarder)dynamic;

								if (propFile != null && propFile.trim().length() > 0) {
									try {
										Properties properties = new Properties();
										properties.load(new FileReader(propFile));
										forwarder.setProperties(properties);
									} catch (Exception ex) {
										// Send message
										response.setStatus(HTTPServer.Response.BAD_REQUEST);
										RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
										ex.printStackTrace();
									}
								}
								nmeaDataForwarders.add(forwarder);
								String content = new Gson().toJson(forwarder.getBean());
								RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
								response.setPayload(content.getBytes());
							} else {
								// Wrong class
								response.setStatus(HTTPServer.Response.BAD_REQUEST);
								RESTProcessorUtil.addErrorMessageToResponse(response, String.format("Expected a Forwarder, found a [%s] instead.", dynamic.getClass().getName()));
							}
						} catch (Exception ex) {
							response.setStatus(HTTPServer.Response.BAD_REQUEST);
							RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
							ex.printStackTrace();
						}
					} else {
						// Already there
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "this 'custom' channel already exists");
					}
				} else {
					// Unknown object, not a Map...
				}
				break;
			default:
				response.setStatus(HTTPServer.Response.NOT_IMPLEMENTED);
				RESTProcessorUtil.addErrorMessageToResponse(response, "'" + type + "' not implemented");
				break;
		}
		return response;
	}

	private HTTPServer.Response postChannel(HTTPServer.Request request) {
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.STATUS_OK);
		Optional<NMEAClient> opClient = null;
		String type = "";
		if (request.getContent() == null || request.getContent().length == 0) {
			response.setStatus(HTTPServer.Response.BAD_REQUEST);
			RESTProcessorUtil.addErrorMessageToResponse(response, "missing payload");
			return response;
		} else {
			Object bean = new GsonBuilder().create().fromJson(new String(request.getContent()), Object.class);
			if (bean instanceof Map) {
				type = ((Map<String, String>) bean).get("type");
			}
		}
		switch (type) {
			case "tcp":
				TCPClient.TCPBean tcpJson = new Gson().fromJson(new String(request.getContent()), TCPClient.TCPBean.class);
				opClient = nmeaDataClients.stream()
								.filter(channel -> channel instanceof SerialClient &&
												((TCPClient.TCPBean) ((TCPClient) channel).getBean()).getPort() == tcpJson.getPort() &&
												((TCPClient.TCPBean) ((TCPClient) channel).getBean()).getHostname().equals(tcpJson.getHostname()))
								.findFirst();
				if (!opClient.isPresent()) {
					try {
						NMEAClient tcpClient = new TCPClient(tcpJson.getDeviceFilters(), tcpJson.getSentenceFilters(), this.mux);
						tcpClient.initClient();
						tcpClient.setReader(new TCPReader(tcpClient.getListeners(), tcpJson.getHostname(), tcpJson.getPort()));
						nmeaDataClients.add(tcpClient);
						tcpClient.startWorking();
						String content = new Gson().toJson(tcpClient.getBean());
						RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
						response.setPayload(content.getBytes());
					} catch (Exception ex) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
						ex.printStackTrace();
					}
				} else {
					// Already there
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, "ths 'tcp' already exists");
				}
				break;
			case "serial":
				SerialClient.SerialBean serialJson = new Gson().fromJson(new String(request.getContent()), SerialClient.SerialBean.class);
				opClient = nmeaDataClients.stream()
								.filter(channel -> channel instanceof SerialClient &&
												((SerialClient.SerialBean) ((SerialClient) channel).getBean()).getPort().equals(serialJson.getPort()))
								.findFirst();
				if (!opClient.isPresent()) {
					try {
						NMEAClient serialClient = new SerialClient(serialJson.getDeviceFilters(), serialJson.getSentenceFilters(),this.mux);
						serialClient.initClient();
						serialClient.setReader(new SerialReader(serialClient.getListeners(), serialJson.getPort(), serialJson.getBr()));
						nmeaDataClients.add(serialClient);
						serialClient.startWorking();
						String content = new Gson().toJson(serialClient.getBean());
						RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
						response.setPayload(content.getBytes());
					} catch (Exception ex) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
						ex.printStackTrace();
					}
				} else {
					// Already there
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'serial' already exists");
				}
				break;
			case "ws":
				WebSocketClient.WSBean wsJson = new Gson().fromJson(new String(request.getContent()), WebSocketClient.WSBean.class);
				// Check if not there yet.
				opClient = nmeaDataClients.stream()
								.filter(channel -> channel instanceof WebSocketClient &&
												((WebSocketClient.WSBean) ((WebSocketClient) channel).getBean()).getWsUri().equals(wsJson.getWsUri()))
								.findFirst();
				if (!opClient.isPresent()) {
					try {
						NMEAClient wsClient = new WebSocketClient(wsJson.getDeviceFilters(), wsJson.getSentenceFilters(),this.mux);
						wsClient.initClient();
						wsClient.setReader(new WebSocketReader(wsClient.getListeners(), wsJson.getWsUri()));
						nmeaDataClients.add(wsClient);
						wsClient.startWorking();
						String content = new Gson().toJson(wsClient.getBean());
						RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
						response.setPayload(content.getBytes());
					} catch (Exception ex) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
						ex.printStackTrace();
					}
				} else {
					// Already there
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'ws' already exists");
				}
				break;
			case "file":
				DataFileClient.DataFileBean fileJson = new Gson().fromJson(new String(request.getContent()), DataFileClient.DataFileBean.class);
				// Check if not there yet.
				opClient = nmeaDataClients.stream()
								.filter(channel -> channel instanceof DataFileClient &&
												((DataFileClient.DataFileBean) ((DataFileClient) channel).getBean()).getFile().equals(fileJson.getFile()))
								.findFirst();
				if (!opClient.isPresent()) {
					try {
						NMEAClient fileClient = new DataFileClient(fileJson.getDeviceFilters(), fileJson.getSentenceFilters(),this.mux);
						fileClient.initClient();
						fileClient.setReader(new DataFileReader(fileClient.getListeners(), fileJson.getFile(), fileJson.getPause()));
						nmeaDataClients.add(fileClient);
						fileClient.startWorking();
						String content = new Gson().toJson(fileClient.getBean());
						RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
						response.setPayload(content.getBytes());
					} catch (Exception ex) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
						ex.printStackTrace();
					}
				} else {
					// Already there
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'file' already exists");
				}
				break;
			case "bmp180":
				BMP180Client.BMP180Bean bmp180Json = new Gson().fromJson(new String(request.getContent()), BMP180Client.BMP180Bean.class);
				opClient = nmeaDataClients.stream()
								.filter(channel -> channel instanceof BMP180Client)
								.findFirst();
				if (!opClient.isPresent()) {
					try {
						NMEAClient bmp180Client = new BMP180Client(bmp180Json.getDeviceFilters(), bmp180Json.getSentenceFilters(),this.mux);
						bmp180Client.initClient();
						bmp180Client.setReader(new BMP180Reader(bmp180Client.getListeners()));
						// To do BEFORE startWorking and AFTER setReader
						if (bmp180Json.getDevicePrefix() != null) {
							if (bmp180Json.getDevicePrefix().trim().length() != 2) {
								throw new RuntimeException(String.format("Device prefix length must be exactly 2. [%s] is not valid", bmp180Json.getDevicePrefix().trim()));
							} else {
								((BMP180Client)bmp180Client).setSpecificDevicePrefix(bmp180Json.getDevicePrefix().trim());
							}
						}
						nmeaDataClients.add(bmp180Client);
						bmp180Client.startWorking();
						String content = new Gson().toJson(bmp180Client.getBean());
						RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
						response.setPayload(content.getBytes());
					} catch (Exception ex) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
						ex.printStackTrace();
					} catch (Error error) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "Maybe you are not on a Raspberry PI...");
						error.printStackTrace();
					}
				} else {
					// Already there
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'bmp180' already exists");
				}
				break;
			case "lsm303":
				LSM303Client.LSM303Bean lsm303Json = new Gson().fromJson(new String(request.getContent()), LSM303Client.LSM303Bean.class);
				opClient = nmeaDataClients.stream()
								.filter(channel -> channel instanceof LSM303Client)
								.findFirst();
				if (!opClient.isPresent()) {
					try {
						NMEAClient lsm303Client = new LSM303Client(lsm303Json.getDeviceFilters(), lsm303Json.getSentenceFilters(),this.mux);
						lsm303Client.initClient();
						lsm303Client.setReader(new LSM303Reader(lsm303Client.getListeners()));
						// To do BEFORE startWorking and AFTER setReader
						if (lsm303Json.getDevicePrefix() != null) {
							if (lsm303Json.getDevicePrefix().trim().length() != 2) {
								throw new RuntimeException(String.format("Device prefix length must be exactly 2. [%s] is not valid", lsm303Json.getDevicePrefix().trim()));
							} else {
								((LSM303Client)lsm303Client).setSpecificDevicePrefix(lsm303Json.getDevicePrefix().trim());
							}
						}
						nmeaDataClients.add(lsm303Client);
						lsm303Client.startWorking();
						String content = new Gson().toJson(lsm303Client.getBean());
						RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
						response.setPayload(content.getBytes());
					} catch (Exception ex) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
						ex.printStackTrace();
					} catch (Error error) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "Maybe you are not on a Raspberry PI...");
						error.printStackTrace();
					}
				} else {
					// Already there
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'lsm303' already exists");
				}
				break;
			case "zda":
				ZDAClient.ZDABean zdaJson = new Gson().fromJson(new String(request.getContent()), ZDAClient.ZDABean.class);
				opClient = nmeaDataClients.stream()
								.filter(channel -> channel instanceof ZDAClient)
								.findFirst();
				if (!opClient.isPresent()) {
					try {
						NMEAClient zdaClient = new ZDAClient(zdaJson.getDeviceFilters(), zdaJson.getSentenceFilters(), this.mux);
						zdaClient.initClient();
						zdaClient.setReader(new ZDAReader(zdaClient.getListeners()));
						// To do BEFORE startWorking and AFTER setReader
						if (zdaJson.getDevicePrefix() != null) {
							if (zdaJson.getDevicePrefix().trim().length() != 2) {
								throw new RuntimeException(String.format("Device prefix length must be exactly 2. [%s] is not valid", zdaJson.getDevicePrefix().trim()));
							} else {
								((ZDAClient)zdaClient).setSpecificDevicePrefix(zdaJson.getDevicePrefix().trim());
							}
						}
						nmeaDataClients.add(zdaClient);
						zdaClient.startWorking();
						String content = new Gson().toJson(zdaClient.getBean());
						RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
						response.setPayload(content.getBytes());
					} catch (Exception ex) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
						ex.printStackTrace();
					} catch (Error error) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "Maybe you are not on a Raspberry PI...");
						error.printStackTrace();
					}
				} else {
					// Already there
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'zda' already exists");
				}
				break;
			case "bme280":
				BME280Client.BME280Bean bme280Json = new Gson().fromJson(new String(request.getContent()), BME280Client.BME280Bean.class);
				opClient = nmeaDataClients.stream()
								.filter(channel -> channel instanceof BME280Client)
								.findFirst();
				if (!opClient.isPresent()) {
					try {
						NMEAClient bme280Client = new BME280Client(bme280Json.getDeviceFilters(), bme280Json.getSentenceFilters(),this.mux);
						bme280Client.initClient();
						bme280Client.setReader(new BME280Reader(bme280Client.getListeners()));
						// To do BEFORE startWorking and AFTER setReader
						if (bme280Json.getDevicePrefix() != null) {
							if (bme280Json.getDevicePrefix().trim().length() != 2) {
								throw new RuntimeException(String.format("Device prefix length must be exactly 2. [%s] is not valid", bme280Json.getDevicePrefix().trim()));
							} else {
								((BME280Client)bme280Client).setSpecificDevicePrefix(bme280Json.getDevicePrefix().trim());
							}
						}
						nmeaDataClients.add(bme280Client);
						bme280Client.startWorking();
						String content = new Gson().toJson(bme280Client.getBean());
						RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
						response.setPayload(content.getBytes());
					} catch (Exception ex) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
						ex.printStackTrace();
					} catch (Error error) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "Maybe you are not on a Raspberry PI...");
						error.printStackTrace();
					}
				} else {
					// Already there
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'bme280' already exists");
				}
				break;
			case "htu21df":
				HTU21DFClient.HTU21DFBean htu21dfJson = new Gson().fromJson(new String(request.getContent()), HTU21DFClient.HTU21DFBean.class);
				opClient = nmeaDataClients.stream()
								.filter(channel -> channel instanceof HTU21DFClient)
								.findFirst();
				if (!opClient.isPresent()) {
					try {
						NMEAClient htu21dfClient = new HTU21DFClient(htu21dfJson.getDeviceFilters(), htu21dfJson.getSentenceFilters(),this.mux);
						htu21dfClient.initClient();
						htu21dfClient.setReader(new HTU21DFReader(htu21dfClient.getListeners()));
						// To do BEFORE startWorking and AFTER setReader
						if (htu21dfJson.getDevicePrefix() != null) {
							if (htu21dfJson.getDevicePrefix().trim().length() != 2) {
								throw new RuntimeException(String.format("Device prefix length must be exactly 2. [%s] is not valid", htu21dfJson.getDevicePrefix().trim()));
							} else {
								((HTU21DFClient)htu21dfClient).setSpecificDevicePrefix(htu21dfJson.getDevicePrefix().trim());
							}
						}
						nmeaDataClients.add(htu21dfClient);
						htu21dfClient.startWorking();
						String content = new Gson().toJson(htu21dfClient.getBean());
						RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
						response.setPayload(content.getBytes());
					} catch (Exception ex) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
						ex.printStackTrace();
					} catch (Error error) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "Maybe you are not on a Raspberry PI...");
						error.printStackTrace();
					}
				} else {
					// Already there
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'htu21df' already exists");
				}
				break;
			case "rnd":
				RandomClient.RandomBean rndJson = new Gson().fromJson(new String(request.getContent()), RandomClient.RandomBean.class);
				opClient = nmeaDataClients.stream()
								.filter(channel -> channel instanceof RandomClient)
								.findFirst();
				if (!opClient.isPresent()) {
					try {
						NMEAClient rndClient = new RandomClient(rndJson.getDeviceFilters(), rndJson.getSentenceFilters(),this.mux);
						rndClient.initClient();
						rndClient.setReader(new RandomReader(rndClient.getListeners()));
						nmeaDataClients.add(rndClient);
						rndClient.startWorking();
						String content = new Gson().toJson(rndClient.getBean());
						RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
						response.setPayload(content.getBytes());
					} catch (Exception ex) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
						ex.printStackTrace();
					}
				} else {
					// Already there
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'rnd' already exists");
				}
				break;
			case "custom":
				String payload = new String(request.getContent());
				Object custom = new Gson().fromJson(payload, Object.class);
				if (custom instanceof Map) {
					@SuppressWarnings("unchecked")
					Map<String, Object> map = (Map<String, Object>)custom;
					String clientClass = ((String)map.get("clientClass")).trim();
					String readerClass = ((String)map.get("readerClass")).trim();
					String propFile = (String)map.get("propFile");
					List<String> deviceFilters = (List<String>)map.get("deviceFilters");
					List<String> sentenceFilters = (List<String>)map.get("sentenceFilters");
					// Make sure client and reader are not null
					if (clientClass == null || clientClass.length() == 0 || readerClass == null || readerClass.length() == 0) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "Require at least both class and reader name.");
						return response;
					}
					// Check Existence
					opClient = nmeaDataClients.stream()
									.filter(channel -> channel.getClass().getName().equals(clientClass))
									.findFirst();
					if (!opClient.isPresent()) {
						try {
							// Create
							String[] devFilters = null;
							String[] senFilters = null;
							if (deviceFilters != null && deviceFilters.size() > 0 && deviceFilters.get(0).length() > 0) {
								List<String> devList = deviceFilters.stream().map(df -> df.trim()).collect(Collectors.toList());
								devFilters = new String[devList.size()];
								devFilters = devList.toArray(devFilters);
							}
							if (sentenceFilters != null && sentenceFilters.size() > 0 && sentenceFilters.get(0).length() > 0) {
								List<String> senList = sentenceFilters.stream().map(df -> df.trim()).collect(Collectors.toList());
								senFilters = new String[senList.size()];
								senFilters = senList.toArray(senFilters);
							}
							Object dynamic = Class.forName(clientClass)
											.getDeclaredConstructor(String[].class, String[].class, Multiplexer.class)
											.newInstance(devFilters, senFilters, this);
							if (dynamic instanceof NMEAClient) {
								NMEAClient nmeaClient = (NMEAClient)dynamic;

								if (propFile != null && propFile.trim().length() > 0) {
									try {
										Properties properties = new Properties();
										properties.load(new FileReader(propFile));
										nmeaClient.setProperties(properties);
									} catch (Exception ex) {
										// Send message
										response.setStatus(HTTPServer.Response.BAD_REQUEST);
										RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
										ex.printStackTrace();
									}
								}
								nmeaClient.initClient();
								NMEAReader reader = null;
								try {
									// Cannot invoke declared constructor with a generic type... :(
									reader = (NMEAReader)Class.forName(readerClass).getDeclaredConstructor(List.class).newInstance(nmeaClient.getListeners());
								} catch (Exception ex) {
									response.setStatus(HTTPServer.Response.BAD_REQUEST);
									RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
									ex.printStackTrace();
								}
								if (reader != null) {
									nmeaClient.setReader(reader);
								}
								nmeaDataClients.add(nmeaClient);
								nmeaClient.startWorking();
								String content = new Gson().toJson(nmeaClient.getBean());
								RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
								response.setPayload(content.getBytes());
							} else {
								// Wrong class
								response.setStatus(HTTPServer.Response.BAD_REQUEST);
								RESTProcessorUtil.addErrorMessageToResponse(response, String.format("Expected an NMEAClient, found a [%s] instead.", dynamic.getClass().getName()));
							}
						} catch (Exception ex) {
							response.setStatus(HTTPServer.Response.BAD_REQUEST);
							RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
							ex.printStackTrace();
						}
					} else {
						// Already there
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "this 'custom' channel already exists");
					}
				} else {
					// Unknown object, not a Map...
				}
				break;
			default:
				response.setStatus(HTTPServer.Response.NOT_IMPLEMENTED);
				break;
		}
		return response;
	}

	private HTTPServer.Response postComputer(HTTPServer.Request request) {
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.STATUS_OK);
		Optional<Computer> opComputer = null;
		String type = "";
		if (request.getContent() == null || request.getContent().length == 0) {
			response.setStatus(HTTPServer.Response.BAD_REQUEST);
			RESTProcessorUtil.addErrorMessageToResponse(response, "missing payload");
			return response;
		} else {
			Object bean = new GsonBuilder().create().fromJson(new String(request.getContent()), Object.class);
			if (bean instanceof Map) {
				type = ((Map<String, String>) bean).get("type");
			}
		}
		switch (type) {
			case "tw-current":
				ExtraDataComputer.ComputerBean twJson = new Gson().fromJson(new String(request.getContent()), ExtraDataComputer.ComputerBean.class);
				// Check if not there yet.
				opComputer = nmeaDataComputers.stream()
								.filter(channel -> channel instanceof ExtraDataComputer)
								.findFirst();
				if (!opComputer.isPresent()) {
					try {
						String[] timeBuffers = twJson.getTimeBufferLength().split(",");
						List<Long> timeBufferLengths  = Arrays.asList(timeBuffers).stream().map(tbl -> Long.parseLong(tbl.trim())).collect(Collectors.toList());
						// Check duplicates
						for (int i=0; i<timeBufferLengths.size() - 1; i++) {
							for (int j=i+1; j< timeBufferLengths.size(); j++) {
								if (timeBufferLengths.get(i).equals(timeBufferLengths.get(j))) {
									throw new RuntimeException(String.format("Duplicates in time buffer lengths: %d ms.", timeBufferLengths.get(i)));
								}
							}
						}
						Computer twCurrentComputer = new ExtraDataComputer(this.mux, twJson.getPrefix(), timeBufferLengths.toArray(new Long[timeBufferLengths.size()]));
						nmeaDataComputers.add(twCurrentComputer);
						String content = new Gson().toJson(twCurrentComputer.getBean());
						RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
						response.setPayload(content.getBytes());
					} catch (Exception ex) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
						ex.printStackTrace();
					}
				} else {
					// Already there
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'computer' already exists");
				}
				break;
			case "custom":
				String payload = new String(request.getContent());
				Object custom = new Gson().fromJson(payload, Object.class);
				if (custom instanceof Map) {
					@SuppressWarnings("unchecked")
					Map<String, String> map = (Map<String, String>)custom;
					String computerClass = map.get("computerClass").trim();
					String propFile = map.get("propFile");
					// Make sure client and reader are not null
					if (computerClass == null || computerClass.length() == 0) {
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "Require at least class name.");
						return response;
					}
					// Check Existence
					opComputer = nmeaDataComputers.stream()
									.filter(fwd -> fwd.getClass().getName().equals(computerClass))
									.findFirst();
					if (!opComputer.isPresent()) {
						try {
							// Create
							Object dynamic = Class.forName(computerClass).getDeclaredConstructor(Multiplexer.class).newInstance(this);
							if (dynamic instanceof Computer) {
								Computer computer = (Computer)dynamic;

								if (propFile != null && propFile.trim().length() > 0) {
									try {
										Properties properties = new Properties();
										properties.load(new FileReader(propFile));
										computer.setProperties(properties);
									} catch (Exception ex) {
										// Send message
										response.setStatus(HTTPServer.Response.BAD_REQUEST);
										RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
										ex.printStackTrace();
									}
								}
								nmeaDataComputers.add(computer);
								String content = new Gson().toJson(computer.getBean());
								RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
								response.setPayload(content.getBytes());
							} else {
								// Wrong class
								response.setStatus(HTTPServer.Response.BAD_REQUEST);
								RESTProcessorUtil.addErrorMessageToResponse(response, String.format("Expected a Computer, found a [%s] instead.", dynamic.getClass().getName()));
							}
						} catch (Exception ex) {
							response.setStatus(HTTPServer.Response.BAD_REQUEST);
							RESTProcessorUtil.addErrorMessageToResponse(response, ex.toString());
							ex.printStackTrace();
						}
					} else {
						// Already there
						response.setStatus(HTTPServer.Response.BAD_REQUEST);
						RESTProcessorUtil.addErrorMessageToResponse(response, "this 'custom' channel already exists");
					}
				} else {
					// Unknown object, not a Map...
				}
				break;
			default:
				response.setStatus(HTTPServer.Response.NOT_IMPLEMENTED);
				break;
		}
		return response;
	}

	private HTTPServer.Response putChannel(HTTPServer.Request request) {
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.STATUS_OK);
		Optional<NMEAClient> opClient = null;
		String type = "";
		if (request.getContent() == null || request.getContent().length == 0) {
			response.setStatus(HTTPServer.Response.BAD_REQUEST);
			RESTProcessorUtil.addErrorMessageToResponse(response, "missing payload");
			return response;
		} else {
			Object bean = new GsonBuilder().create().fromJson(new String(request.getContent()), Object.class);
			if (bean instanceof Map) {
				type = ((Map<String, String>) bean).get("type");
			}
			List<String> prmValues = RESTProcessorUtil.getPrmValues(request.getRequestPattern(), request.getPath());
			if (prmValues.size() == 1) {
				String id = prmValues.get(0);
				if (!type.equals(id)) {
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, String.format("path and payload do not match. path:[%s], payload:[%s]", id, type));
					return response;
				}
			} else {
				response.setStatus(HTTPServer.Response.BAD_REQUEST);
				RESTProcessorUtil.addErrorMessageToResponse(response, "required path parameter was not found");
				return response;
			}
		}
		switch (type) {
			case "serial":
				SerialClient.SerialBean serialJson = new Gson().fromJson(new String(request.getContent()), SerialClient.SerialBean.class);
				opClient = nmeaDataClients.stream()
								.filter(channel -> channel instanceof SerialClient &&
												((SerialClient.SerialBean) ((SerialClient) channel).getBean()).getPort().equals(serialJson.getPort()))
								.findFirst();
				if (!opClient.isPresent()) {
					response.setStatus(HTTPServer.Response.NOT_FOUND);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'serial' was not found");
				} else { // Then update
					SerialClient serialClient = (SerialClient) opClient.get();
					serialClient.setVerbose(serialJson.getVerbose());
					String content = new Gson().toJson(serialClient.getBean());
					RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
					response.setPayload(content.getBytes());
				}
				break;
			case "file":
				DataFileClient.DataFileBean fileJson = new Gson().fromJson(new String(request.getContent()), DataFileClient.DataFileBean.class);
				opClient = nmeaDataClients.stream()
								.filter(channel -> channel instanceof DataFileClient &&
												((DataFileClient.DataFileBean) ((DataFileClient) channel).getBean()).getFile().equals(fileJson.getFile()))
								.findFirst();
				if (!opClient.isPresent()) {
					response.setStatus(HTTPServer.Response.NOT_FOUND);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'file' was not found");
				} else { // Then update
					DataFileClient dataFileClient = (DataFileClient) opClient.get();
					dataFileClient.setVerbose(fileJson.getVerbose());
					String content = new Gson().toJson(dataFileClient.getBean());
					RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
					response.setPayload(content.getBytes());
				}
				break;
			case "tcp":
				TCPClient.TCPBean tcpJson = new Gson().fromJson(new String(request.getContent()), TCPClient.TCPBean.class);
				opClient = nmeaDataClients.stream()
								.filter(channel -> channel instanceof TCPClient &&
												((TCPClient.TCPBean) ((TCPClient) channel).getBean()).getHostname().equals(tcpJson.getHostname()) &&
												((TCPClient.TCPBean) ((TCPClient) channel).getBean()).getPort() == tcpJson.getPort())
								.findFirst();
				if (!opClient.isPresent()) {
					response.setStatus(HTTPServer.Response.NOT_FOUND);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'tcp' was not found");
				} else { // Then update
					TCPClient tcpClient = (TCPClient) opClient.get();
					tcpClient.setVerbose(tcpJson.getVerbose());
					String content = new Gson().toJson(tcpClient.getBean());
					RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
					response.setPayload(content.getBytes());
				}
				break;
			case "ws":
				WebSocketClient.WSBean wsJson = new Gson().fromJson(new String(request.getContent()), WebSocketClient.WSBean.class);
				opClient = nmeaDataClients.stream()
								.filter(channel -> channel instanceof WebSocketClient &&
												((WebSocketClient.WSBean) ((WebSocketClient) channel).getBean()).getWsUri().equals(wsJson.getWsUri()))
								.findFirst();
				if (!opClient.isPresent()) {
					response.setStatus(HTTPServer.Response.NOT_FOUND);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'ws' was not found");
				} else { // Then update
					WebSocketClient webSocketClient = (WebSocketClient) opClient.get();
					webSocketClient.setVerbose(wsJson.getVerbose());
					String content = new Gson().toJson(webSocketClient.getBean());
					RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
					response.setPayload(content.getBytes());
				}
				break;
			case "bmp180":
				BMP180Client.BMP180Bean bmp180Json = new Gson().fromJson(new String(request.getContent()), BMP180Client.BMP180Bean.class);
				opClient = nmeaDataClients.stream()
								.filter(channel -> channel instanceof BMP180Client)
								.findFirst();
				if (!opClient.isPresent()) {
					response.setStatus(HTTPServer.Response.NOT_FOUND);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'bmp180' was not found");
				} else { // Then update
					BMP180Client bmp180Client = (BMP180Client) opClient.get();
					bmp180Client.setVerbose(bmp180Json.getVerbose());
					String content = new Gson().toJson(bmp180Client.getBean());
					RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
					response.setPayload(content.getBytes());
				}
				break;
			case "bme280":
				BME280Client.BME280Bean bme280Json = new Gson().fromJson(new String(request.getContent()), BME280Client.BME280Bean.class);
				opClient = nmeaDataClients.stream()
								.filter(channel -> channel instanceof BME280Client)
								.findFirst();
				if (!opClient.isPresent()) {
					response.setStatus(HTTPServer.Response.NOT_FOUND);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'bme280' was not found");
				} else { // Then update
					BME280Client bme280Client = (BME280Client) opClient.get();
					bme280Client.setVerbose(bme280Json.getVerbose());
					String content = new Gson().toJson(bme280Client.getBean());
					RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
					response.setPayload(content.getBytes());
				}
				break;
			case "lsm303":
				LSM303Client.LSM303Bean lsm303Json = new Gson().fromJson(new String(request.getContent()), LSM303Client.LSM303Bean.class);
				opClient = nmeaDataClients.stream()
								.filter(channel -> channel instanceof LSM303Client)
								.findFirst();
				if (!opClient.isPresent()) {
					response.setStatus(HTTPServer.Response.NOT_FOUND);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'lsm303' was not found");
				} else { // Then update
					LSM303Client lsm303Client = (LSM303Client) opClient.get();
					lsm303Client.setVerbose(lsm303Json.getVerbose());
					String content = new Gson().toJson(lsm303Client.getBean());
					RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
					response.setPayload(content.getBytes());
				}
				break;
			case "zda":
				ZDAClient.ZDABean zdaJson = new Gson().fromJson(new String(request.getContent()), ZDAClient.ZDABean.class);
				opClient = nmeaDataClients.stream()
								.filter(channel -> channel instanceof ZDAClient)
								.findFirst();
				if (!opClient.isPresent()) {
					response.setStatus(HTTPServer.Response.NOT_FOUND);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'da' was not found");
				} else { // Then update
					ZDAClient zdaClient = (ZDAClient) opClient.get();
					zdaClient.setVerbose(zdaJson.getVerbose());
					String content = new Gson().toJson(zdaClient.getBean());
					RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
					response.setPayload(content.getBytes());
				}
				break;
			case "htu21df":
				HTU21DFClient.HTU21DFBean htu21dfJson = new Gson().fromJson(new String(request.getContent()), HTU21DFClient.HTU21DFBean.class);
				opClient = nmeaDataClients.stream()
								.filter(channel -> channel instanceof HTU21DFClient)
								.findFirst();
				if (!opClient.isPresent()) {
					response.setStatus(HTTPServer.Response.NOT_FOUND);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'htu21df' was not found");
				} else { // Then update
					HTU21DFClient htu21DFClient = (HTU21DFClient) opClient.get();
					htu21DFClient.setVerbose(htu21dfJson.getVerbose());
					String content = new Gson().toJson(htu21DFClient.getBean());
					RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
					response.setPayload(content.getBytes());
				}
				break;
			case "rnd":
				RandomClient.RandomBean rndJson = new Gson().fromJson(new String(request.getContent()), RandomClient.RandomBean.class);
				opClient = nmeaDataClients.stream()
								.filter(channel -> channel instanceof RandomClient)
								.findFirst();
				if (!opClient.isPresent()) {
					response.setStatus(HTTPServer.Response.NOT_FOUND);
					RESTProcessorUtil.addErrorMessageToResponse(response, "this 'rnd' was not found");
				} else { // Then update
					RandomClient randomClient = (RandomClient) opClient.get();
					randomClient.setVerbose(rndJson.getVerbose());
					String content = new Gson().toJson(randomClient.getBean());
					RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
					response.setPayload(content.getBytes());
				}
				break;
			default:
				@SuppressWarnings("unchecked")
				Map<String, Object> custom = (Map<String, Object>)new Gson().fromJson(new String(request.getContent()), Object.class);
				opClient = nmeaDataClients.stream()
								.filter(cptr -> cptr.getClass().getName().equals(custom.get("cls")))
								.findFirst();
				if (!opClient.isPresent()) {
					response.setStatus(HTTPServer.Response.NOT_FOUND);
					RESTProcessorUtil.addErrorMessageToResponse(response, "'custom' not found");
				} else { // Then update
					NMEAClient nmeaClient = opClient.get();
					boolean verbose = ((Boolean)custom.get("verbose")).booleanValue();
					nmeaClient.setVerbose(verbose);
					String content = new Gson().toJson(nmeaClient.getBean());
					RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
					response.setPayload(content.getBytes());
				}
//			response.setStatus(HTTPServer.Response.NOT_IMPLEMENTED);
				break;
		}
		return response;
	}

	private HTTPServer.Response putForwarder(HTTPServer.Request request) {
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.STATUS_OK);
		Optional<NMEAClient> opClient = null;
		String type = "";
		if (request.getContent() == null || request.getContent().length == 0) {
			response.setStatus(HTTPServer.Response.BAD_REQUEST);
			RESTProcessorUtil.addErrorMessageToResponse(response, "missing payload");
			return response;
		} else {
			Object bean = new GsonBuilder().create().fromJson(new String(request.getContent()), Object.class);
			if (bean instanceof Map) {
				type = ((Map<String, String>) bean).get("type");
			}
			List<String> prmValues = RESTProcessorUtil.getPrmValues(request.getRequestPattern(), request.getPath());
			if (prmValues.size() == 1) {
				String id = prmValues.get(0);
				if (!type.equals(id)) {
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, String.format("path and payload do not match. path:[%s], payload:[%s]", id, type));
					return response;
				}
			} else {
				response.setStatus(HTTPServer.Response.BAD_REQUEST);
				RESTProcessorUtil.addErrorMessageToResponse(response, "required path parameter was not found");
				return response;
			}
		}
		switch (type) {
			default:
				response.setStatus(HTTPServer.Response.NOT_IMPLEMENTED);
				break;
		}
		return response;
	}

	private HTTPServer.Response putComputer(HTTPServer.Request request) {
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.STATUS_OK);
		Optional<Computer> opComputer = null;
		String type = "";
		if (request.getContent() == null || request.getContent().length == 0) {
			response.setStatus(HTTPServer.Response.BAD_REQUEST);
			RESTProcessorUtil.addErrorMessageToResponse(response, "missing payload");
			return response;
		} else {
			Object bean = new GsonBuilder().create().fromJson(new String(request.getContent()), Object.class);
			if (bean instanceof Map) {
				type = ((Map<String, String>) bean).get("type");
			}
			List<String> prmValues = RESTProcessorUtil.getPrmValues(request.getRequestPattern(), request.getPath());
			if (prmValues.size() == 1) {
				String id = prmValues.get(0);
				if (!type.equals(id)) {
					response.setStatus(HTTPServer.Response.BAD_REQUEST);
					RESTProcessorUtil.addErrorMessageToResponse(response, String.format("path and payload do not match. path:[%s], payload:[%s]", id, type));
					return response;
				}
			} else {
				response.setStatus(HTTPServer.Response.BAD_REQUEST);
				RESTProcessorUtil.addErrorMessageToResponse(response, "required path parameter was not found");
				return response;
			}
		}
		switch (type) {
			case "tw-current":
				ExtraDataComputer.ComputerBean twJson = new Gson().fromJson(new String(request.getContent()), ExtraDataComputer.ComputerBean.class);
				opComputer = nmeaDataComputers.stream()
								.filter(cptr -> cptr instanceof ExtraDataComputer)
								.findFirst();
				if (!opComputer.isPresent()) {
					response.setStatus(HTTPServer.Response.NOT_FOUND);
					RESTProcessorUtil.addErrorMessageToResponse(response, "'tw-current' not found");
				} else { // Then update
					ExtraDataComputer computer = (ExtraDataComputer) opComputer.get();
					computer.setVerbose(twJson.isVerbose());
					computer.setPrefix(twJson.getPrefix());
					String content = new Gson().toJson(computer.getBean());
					RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
					response.setPayload(content.getBytes());
				}
				break;
			default:
				@SuppressWarnings("unchecked")
				Map<String, Object> custom = (Map<String, Object>)new Gson().fromJson(new String(request.getContent()), Object.class);
				opComputer = nmeaDataComputers.stream()
								.filter(cptr -> cptr.getClass().getName().equals(custom.get("cls")))
								.findFirst();
				if (!opComputer.isPresent()) {
					response.setStatus(HTTPServer.Response.NOT_FOUND);
					RESTProcessorUtil.addErrorMessageToResponse(response, "'custom' not found");
				} else { // Then update
					Computer computer = opComputer.get();
					boolean verbose = ((Boolean)custom.get("verbose")).booleanValue();
					computer.setVerbose(verbose);
					String content = new Gson().toJson(computer.getBean());
					RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
					response.setPayload(content.getBytes());
				}
//			response.setStatus(HTTPServer.Response.NOT_IMPLEMENTED);
				break;
		}
		return response;
	}

	private HTTPServer.Response putMuxVerbose(HTTPServer.Request request) {
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.STATUS_OK);
		List<String> prmValues = RESTProcessorUtil.getPrmValues(request.getRequestPattern(), request.getPath());
		if (prmValues.size() != 1) {
			response.setStatus(HTTPServer.Response.BAD_REQUEST);
			RESTProcessorUtil.addErrorMessageToResponse(response, "missing path parameter");
			return response;
		}
		boolean newValue = "on".equals(prmValues.get(0));
		this.mux.setVerbose(newValue);
		JsonElement jsonElement = new Gson().toJsonTree(newValue);
		String content = jsonElement.toString();
		RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
		response.setPayload(content.getBytes());

		return response;
	}

	private HTTPServer.Response getCache(HTTPServer.Request request) {
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.STATUS_OK);

		NMEADataCache cache = ApplicationContext.getInstance().getDataCache();

		JsonElement jsonElement = null;
		try {
			// Calculate VMG(s)
			NMEAUtils.calculateVMGs(cache);

			jsonElement = new Gson().toJsonTree(cache);
			((JsonObject) jsonElement).remove(NMEADataCache.DEVIATION_DATA); // Useless for the client.
		} catch (Exception ex) {
			Context.getInstance().getLogger().log(Level.INFO, "Managed >>> getCache", ex);
		}
		String content = jsonElement != null ? jsonElement.toString() : "";
		RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
		response.setPayload(content.getBytes());

		return response;
	}

	private HTTPServer.Response resetCache(HTTPServer.Request request) {
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.NO_CONTENT);

		NMEADataCache cache = ApplicationContext.getInstance().getDataCache();
		cache.reset();
		// Also reset computers
		nmeaDataComputers.stream()
						.filter(channel -> channel instanceof ExtraDataComputer)
						.forEach(extraDataComputer -> ((ExtraDataComputer)extraDataComputer).resetCurrentComputers());
		RESTProcessorUtil.generateHappyResponseHeaders(response, 0);

		return response;
	}

	private HTTPServer.Response getNMEAVolumeStatus(HTTPServer.Request request) {
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.STATUS_OK);

		Map<String, Long> map = new HashMap<>(2);
		map.put("started", Context.getInstance().getStartTime());
		map.put("nmea-bytes", Context.getInstance().getManagedBytes());

		JsonElement jsonElement = null;
		try {
			jsonElement = new Gson().toJsonTree(map);
		} catch (Exception ex) {
			Context.getInstance().getLogger().log(Level.INFO, "Managed >>> getNMEAVolumeStatus", ex);
		}
		String content = jsonElement != null ? jsonElement.toString() : "";
		RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
		response.setPayload(content.getBytes());

		return response;
	}

	private HTTPServer.Response getLastNMEASentence(HTTPServer.Request request) {
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.STATUS_OK);

		Map<String, Object> map = new HashMap<>(2);
		StringAndTimeStamp lastData = Context.getInstance().getLastDataSentence();
		map.put("timestamp", lastData.getTimestamp());
		map.put("last-data", lastData.getString());

		JsonElement jsonElement = null;
		try {
			jsonElement = new Gson().toJsonTree(map);
		} catch (Exception ex) {
			Context.getInstance().getLogger().log(Level.INFO, "Managed >>> getLastNMEASentence", ex);
		}
		String content = jsonElement != null ? jsonElement.toString() : "";
		RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
		response.setPayload(content.getBytes());

		return response;
	}

	private HTTPServer.Response getOperationList(HTTPServer.Request request) {
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.STATUS_OK);
		Operation[] channelArray = operations.stream()
						.collect(Collectors.toList())
						.toArray(new Operation[operations.size()]);
		String content = new Gson().toJson(channelArray);
		RESTProcessorUtil.generateHappyResponseHeaders(response, content.length());
		response.setPayload(content.getBytes());
		return response;
	}

	/**
	 * Use this as a temporary placeholder when creating a new operation.
	 * @param request
	 * @return
	 */
	private HTTPServer.Response emptyOperation(HTTPServer.Request request) {
		HTTPServer.Response response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.STATUS_OK);

		return response;
	}

	private HTTPServer.Response removeForwarderIfPresent(HTTPServer.Request request, Optional<Forwarder> opFwd) {
		HTTPServer.Response response;
		if (opFwd.isPresent()) {
			Forwarder forwarder = opFwd.get();
			forwarder.close();
			nmeaDataForwarders.remove(forwarder);
			response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.NO_CONTENT);
		} else {
			response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.NOT_FOUND);
			RESTProcessorUtil.addErrorMessageToResponse(response, "forwarder not found");
		}
		return response;
	}

	private HTTPServer.Response removeChannelIfPresent(HTTPServer.Request request, Optional<NMEAClient> nmeaClient) {
		HTTPServer.Response response;
		if (nmeaClient.isPresent()) {
			NMEAClient client = nmeaClient.get();
			client.stopDataRead();
			nmeaDataClients.remove(client);
			response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.NO_CONTENT);
		} else {
			response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.NOT_FOUND);
			RESTProcessorUtil.addErrorMessageToResponse(response, "channel not found");
		}
		return response;
	}

	private HTTPServer.Response removeComputerIfPresent(HTTPServer.Request request, Optional<Computer> nmeaComputer) {
		HTTPServer.Response response;
		if (nmeaComputer.isPresent()) {
			Computer computer = nmeaComputer.get();
			computer.close();
			nmeaDataComputers.remove(computer);
			response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.NO_CONTENT);
		} else {
			response = new HTTPServer.Response(request.getProtocol(), HTTPServer.Response.NOT_FOUND);
			RESTProcessorUtil.addErrorMessageToResponse(response, "computer not found");
		}
		return response;
	}

	private static List<String> getSerialPortList() {
		List<String> portList = new ArrayList<>();
		// Opening Serial port
		Enumeration enumeration = CommPortIdentifier.getPortIdentifiers();
		while (enumeration.hasMoreElements()) {
			CommPortIdentifier cpi = (CommPortIdentifier) enumeration.nextElement();
			portList.add(cpi.getName());
		}
		return portList;
	}

	private List<Object> getInputChannelList() {
		return nmeaDataClients.stream().map(nmea -> nmea.getBean()).collect(Collectors.toList());
	}

	private List<Object> getForwarderList() {
		return nmeaDataForwarders.stream().map(fwd -> fwd.getBean()).collect(Collectors.toList());
	}

	private List<Object> getComputerList() {
		return nmeaDataComputers.stream().map(cptr -> cptr.getBean()).collect(Collectors.toList());
	}

}
