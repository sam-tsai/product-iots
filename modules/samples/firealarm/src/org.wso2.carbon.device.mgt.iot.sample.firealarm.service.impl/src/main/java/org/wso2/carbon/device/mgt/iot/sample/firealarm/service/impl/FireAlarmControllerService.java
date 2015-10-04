/*
 * Copyright (c) 2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wso2.carbon.device.mgt.iot.sample.firealarm.service.impl;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.device.mgt.analytics.exception.DataPublisherConfigurationException;
import org.wso2.carbon.device.mgt.analytics.service.DeviceAnalyticsService;
import org.wso2.carbon.device.mgt.common.DeviceIdentifier;
import org.wso2.carbon.device.mgt.common.DeviceManagementException;
import org.wso2.carbon.device.mgt.iot.common.DeviceController;
import org.wso2.carbon.device.mgt.iot.common.DeviceValidator;
import org.wso2.carbon.device.mgt.iot.common.controlqueue.xmpp.XmppConfig;
import org.wso2.carbon.device.mgt.iot.common.datastore.impl.DataStreamDefinitions;
import org.wso2.carbon.device.mgt.iot.common.exception.DeviceControllerException;
import org.wso2.carbon.device.mgt.iot.common.exception.UnauthorizedException;
import org.wso2.carbon.device.mgt.iot.sample.firealarm.plugin.constants.FireAlarmConstants;
import org.wso2.carbon.device.mgt.iot.sample.firealarm.service.impl.util.DeviceJSON;
import org.wso2.carbon.utils.CarbonUtils;

import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;

public class FireAlarmControllerService {

	private static Log log = LogFactory.getLog(FireAlarmControllerService.class);
	//TODO; replace this tenant domain
	private final String SUPER_TENANT = "carbon.super";
	@Context  //injected response proxy supporting multiple thread
	private HttpServletResponse response;
	private static final String TEMPERATURE_STREAM_DEFINITION = "org.wso2.iot.devices.temperature";

	private static final String URL_PREFIX = "http://";
	private static final String BULB_CONTEXT = "/BULB/";
	private static final String SONAR_CONTEXT = "/SONAR/";
	private static final String TEMPERATURE_CONTEXT = "/TEMPERATURE/";

	public static final String XMPP_PROTOCOL = "XMPP";
	public static final String HTTP_PROTOCOL = "HTTP";
	public static final String MQTT_PROTOCOL = "MQTT";


	private static ConcurrentHashMap<String, String> deviceToIpMap =
			new ConcurrentHashMap<String, String>();

	@Path("/register/{owner}/{deviceId}/{ip}")
	@POST
	public String registerDeviceIP(@PathParam("owner") String owner,
								   @PathParam("deviceId") String deviceId,
								   @PathParam("ip") String deviceIP,
								   @Context HttpServletResponse response) {
		String result;

		log.info("Got register call from IP: " + deviceIP + " for Device ID: " + deviceId +
						 " of owner: " + owner);

		deviceToIpMap.put(deviceId, deviceIP);

		result = "Device-IP Registered";
		response.setStatus(Response.Status.OK.getStatusCode());

		if (log.isDebugEnabled()) {
			log.debug(result);
		}

		return result;
	}


	/*    Service to switch "ON" and "OFF" the FireAlarm bulb
		   Called by an external client intended to control the FireAlarm bulb */
	@Path("/bulb/{state}")
	@POST
	public void switchBulb(@HeaderParam("owner") String owner,
						   @HeaderParam("deviceId") String deviceId,
						   @HeaderParam("protocol") String protocol,
						   @PathParam("state") String state,
						   @Context HttpServletResponse response) {

		try {
			DeviceValidator deviceValidator = new DeviceValidator();
			if (!deviceValidator.isExist(owner, SUPER_TENANT, new DeviceIdentifier(deviceId,
																				   FireAlarmConstants.DEVICE_TYPE))) {
				response.setStatus(Response.Status.UNAUTHORIZED.getStatusCode());
				return;
			}
		} catch (DeviceManagementException e) {
			log.error("DeviceValidation Failed for deviceId: " + deviceId + " of user: " + owner);
			response.setStatus(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
			return;
		}

		String switchToState = state.toUpperCase();

		if (!switchToState.equals(FireAlarmConstants.STATE_ON) && !switchToState.equals(
				FireAlarmConstants.STATE_OFF)) {
			log.error("The requested state change shoud be either - 'ON' or 'OFF'");
			response.setStatus(Response.Status.BAD_REQUEST.getStatusCode());
			return;
		}

		String deviceIP = deviceToIpMap.get(deviceId);
		if (deviceIP == null) {
			response.setStatus(Response.Status.PRECONDITION_FAILED.getStatusCode());
			return;
		}

		String protocolString = protocol.toUpperCase();
		String callUrlPattern = BULB_CONTEXT + switchToState;

		log.info("Sending command: '" + callUrlPattern + "' to firealarm at: " + deviceIP + " " +
						 "via" + " " + protocolString);

		try {
			switch (protocolString) {
				case HTTP_PROTOCOL:
					sendCommandViaHTTP(deviceIP, 80, callUrlPattern, true);
					break;
				case MQTT_PROTOCOL:
					sendCommandViaMQTT(owner, deviceId, BULB_CONTEXT.replace("/", ""),
									   switchToState);
					break;
				case XMPP_PROTOCOL:
//					requestBulbChangeViaXMPP(switchToState, response);
					sendCommandViaXMPP(owner, deviceId, BULB_CONTEXT, switchToState);
					break;
				default:
					if (protocolString == null) {
						sendCommandViaHTTP(deviceIP, 80, callUrlPattern, true);
					} else {
						response.setStatus(Response.Status.NOT_ACCEPTABLE.getStatusCode());
						return;
					}
					break;
			}
		} catch (DeviceManagementException e) {
			log.error("Failed to send command '" + callUrlPattern + "' to: " + deviceIP + " via" +
							  " " + protocol);
			response.setStatus(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
			return;
		}

		response.setStatus(Response.Status.OK.getStatusCode());
	}


	@Path("/readsonar")
	@GET
	public String requestSonarReading(@HeaderParam("owner") String owner,
									  @HeaderParam("deviceId") String deviceId,
									  @HeaderParam("protocol") String protocol,
									  @Context HttpServletResponse response) {
		String replyMsg = "";

		DeviceValidator deviceValidator = new DeviceValidator();
		try {
			if (!deviceValidator.isExist(owner, SUPER_TENANT, new DeviceIdentifier(deviceId,
																				   FireAlarmConstants
																						   .DEVICE_TYPE))) {
				response.setStatus(Response.Status.UNAUTHORIZED.getStatusCode());
				return "Unauthorized Access";
			}
		} catch (DeviceManagementException e) {
			replyMsg = e.getErrorMessage();
			response.setStatus(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
			return replyMsg;
		}

		String deviceIp = deviceToIpMap.get(deviceId);

		if (deviceIp == null) {
			replyMsg = "IP not registered for device: " + deviceId + " of owner: " + owner;
			response.setStatus(Response.Status.PRECONDITION_FAILED.getStatusCode());
			return replyMsg;
		}

		try {
			switch (protocol) {
				case HTTP_PROTOCOL:
					log.info("Sending request to read sonar value at : " + deviceIp +
									 " via " + HTTP_PROTOCOL);

					replyMsg = sendCommandViaHTTP(deviceIp, 80, SONAR_CONTEXT, false);
					break;

				case XMPP_PROTOCOL:
					log.info("Sending request to read sonar value at : " + deviceIp +
									 " via " +
									 XMPP_PROTOCOL);
					replyMsg = sendCommandViaXMPP(owner, deviceId, SONAR_CONTEXT, ".");
					break;

				default:
					if (protocol == null) {
						log.info("Sending request to read sonar value at : " + deviceIp +
										 " via " + HTTP_PROTOCOL);

						replyMsg = sendCommandViaHTTP(deviceIp, 80, SONAR_CONTEXT, false);
					} else {
						replyMsg = "Requested protocol '" + protocol + "' is not supported";
						response.setStatus(Response.Status.NOT_ACCEPTABLE.getStatusCode());
						return replyMsg;
					}
					break;
			}
		} catch (DeviceManagementException e) {
			replyMsg = e.getErrorMessage();
			response.setStatus(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
			return replyMsg;
		}

		response.setStatus(Response.Status.OK.getStatusCode());
		replyMsg = "The current sonar reading of the device is " + replyMsg;
		return replyMsg;
	}


	@Path("/readtemperature")
	@GET
	public String requestTemperature(@HeaderParam("owner") String owner,
									 @HeaderParam("deviceId") String deviceId,
									 @HeaderParam("protocol") String protocol,
									 @Context HttpServletResponse response) {
		String replyMsg = "";

		DeviceValidator deviceValidator = new DeviceValidator();
		try {
			if (!deviceValidator.isExist(owner, SUPER_TENANT, new DeviceIdentifier(deviceId,
																				   FireAlarmConstants
																						   .DEVICE_TYPE))) {
				response.setStatus(Response.Status.UNAUTHORIZED.getStatusCode());
				return "Unauthorized Access";
			}
		} catch (DeviceManagementException e) {
			replyMsg = e.getErrorMessage();
			response.setStatus(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
			return replyMsg;
		}

		String deviceIp = deviceToIpMap.get(deviceId);

		if (deviceIp == null) {
			replyMsg = "IP not registered for device: " + deviceId + " of owner: " + owner;
			response.setStatus(Response.Status.PRECONDITION_FAILED.getStatusCode());
			return replyMsg;
		}

		try {
			switch (protocol) {
				case HTTP_PROTOCOL:
					log.info("Sending request to read firealarm-temperature at : " + deviceIp +
									 " via " + HTTP_PROTOCOL);

					replyMsg = sendCommandViaHTTP(deviceIp, 80, TEMPERATURE_CONTEXT, false);
					break;

				case XMPP_PROTOCOL:
					log.info("Sending request to read firealarm-temperature at : " + deviceIp +
									 " via " +
									 XMPP_PROTOCOL);
					replyMsg = sendCommandViaXMPP(owner, deviceId, TEMPERATURE_CONTEXT, ".");
//					replyMsg = requestTemperatureViaXMPP(response);
					break;

				default:
					if (protocol == null) {
						log.info("Sending request to read firealarm-temperature at : " + deviceIp +
										 " via " + HTTP_PROTOCOL);

						replyMsg = sendCommandViaHTTP(deviceIp, 80, TEMPERATURE_CONTEXT, false);
					} else {
						replyMsg = "Requested protocol '" + protocol + "' is not supported";
						response.setStatus(Response.Status.NOT_ACCEPTABLE.getStatusCode());
						return replyMsg;
					}
					break;
			}
		} catch (DeviceManagementException e) {
			replyMsg = e.getErrorMessage();
			response.setStatus(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
			return replyMsg;
		}

		response.setStatus(Response.Status.OK.getStatusCode());
		replyMsg = "The current temperature of the device is " + replyMsg;
		return replyMsg;
	}


//	public String requestTemperatureViaXMPP(@Context HttpServletResponse response) {
//		String replyMsg = "";
//
//		String sep = File.separator;
//		String scriptsFolder = "repository" + sep + "resources" + sep + "scripts";
//		String scriptPath = CarbonUtils.getCarbonHome() + sep + scriptsFolder + sep
//				+ "xmpp_client.py -r Temperature";
//		String command = "python " + scriptPath;
//
//		replyMsg = executeCommand(command);
//
//		response.setStatus(HttpStatus.SC_OK);
//		return replyMsg;
//	}


//	public String requestSonarViaXMPP(@Context HttpServletResponse response) {
//		String replyMsg = "";
//
//		String sep = File.separator;
//		String scriptsFolder = "repository" + sep + "resources" + sep + "scripts";
//		String scriptPath = CarbonUtils.getCarbonHome() + sep + scriptsFolder + sep
//				+ "xmpp_client.py -r Sonar";
//		String command = "python " + scriptPath;
//
//		replyMsg = executeCommand(command);
//
//		response.setStatus(HttpStatus.SC_OK);
//		return replyMsg;
//	}


//	public String requestBulbChangeViaXMPP(String state,
//										   @Context HttpServletResponse response) {
//		String replyMsg = "";
//
//		String sep = File.separator;
//		String scriptsFolder = "repository" + sep + "resources" + sep + "scripts";
//		String scriptPath = CarbonUtils.getCarbonHome() + sep + scriptsFolder + sep
//				+ "xmpp_client.py -r Bulb -s " + state;
//		String command = "python " + scriptPath;
//
//		replyMsg = executeCommand(command);
//
//		response.setStatus(HttpStatus.SC_OK);
//		return replyMsg;
//	}

	@Path("/push_temperature")
	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	public void pushTemperatureData(
			final DeviceJSON dataMsg, @Context HttpServletResponse response) {
		boolean result;
		String deviceId = dataMsg.deviceId;
		String deviceIp = dataMsg.reply;
		float temperature = dataMsg.value;

		String registeredIp = deviceToIpMap.get(deviceId);

		if (registeredIp == null) {
			log.warn("Unregistered IP: Temperature Data Received from an un-registered IP " +
							deviceIp + " for device ID - " + deviceId);
			response.setStatus(Response.Status.PRECONDITION_FAILED.getStatusCode());
			return;
		} else if (!registeredIp.equals(deviceIp)) {
			log.warn("Conflicting IP: Received IP is " + deviceIp + ". Device with ID " +
			deviceId + " is already registered under some other IP. Re-registration " + "required");
			response.setStatus(Response.Status.CONFLICT.getStatusCode());
			return;
		}

		PrivilegedCarbonContext.startTenantFlow();
		PrivilegedCarbonContext ctx = PrivilegedCarbonContext.getThreadLocalCarbonContext();
		ctx.setTenantDomain(SUPER_TENANT, true);
		DeviceAnalyticsService deviceAnalyticsService = (DeviceAnalyticsService) ctx
				.getOSGiService(DeviceAnalyticsService.class, null);
		Object metdaData[] = {dataMsg.owner, FireAlarmConstants.DEVICE_TYPE, dataMsg.deviceId,
				System.currentTimeMillis()};
		Object payloadData[] = {temperature};
		try {
			deviceAnalyticsService.publishEvent(TEMPERATURE_STREAM_DEFINITION, "1.0.0",
												metdaData, new Object[0], payloadData);
		} catch (DataPublisherConfigurationException e) {
			response.setStatus(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());

		} finally {
			PrivilegedCarbonContext.endTenantFlow();
		}


	}


	/*    Service to push all the sensor data collected by the FireAlarm
		   Called by the FireAlarm device  */

//	@Path("/pushalarmdata")
//	@POST
//	@Consumes(MediaType.APPLICATION_JSON)
//	public void pushAlarmData(final DeviceJSON dataMsg, @Context HttpServletResponse response) {
//		boolean result;
//		String sensorValues = dataMsg.value;
//		log.info("Recieved Sensor Data Values: " + sensorValues);
//
//		String sensors[] = sensorValues.split(":");
//		try {
//			if (sensors.length == 3) {
//				String temperature = sensors[0];
//				String bulb = sensors[1];
//				String sonar = sensors[2];

//				sensorValues = "Temperature:" + temperature + "C\tBulb Status:" + bulb +
//						"\t\tSonar Status:" + sonar;
//				log.info(sensorValues);
//				DeviceController deviceController = new DeviceController();
//				result = deviceController.pushBamData(dataMsg.owner, FireAlarmConstants
//															  .DEVICE_TYPE,
//													  dataMsg.deviceId,
//													  System.currentTimeMillis(), "DeviceData",
//													  temperature, "TEMPERATURE");
//
//				if (!result) {
//					response.setStatus(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
//					log.error("Error whilst pushing temperature: " + sensorValues);
//					return;
//				}
//
//				result = deviceController.pushBamData(dataMsg.owner, FireAlarmConstants
//															  .DEVICE_TYPE,
//													  dataMsg.deviceId,
//													  System.currentTimeMillis(), "DeviceData",
//													  bulb,
//													  "BULB");
//
//				if (!result) {
//					response.setStatus(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
//					log.error("Error whilst pushing Bulb data: " + sensorValues);
//					return;
//				}
//
//				result = deviceController.pushBamData(dataMsg.owner, FireAlarmConstants
//															  .DEVICE_TYPE,
//													  dataMsg.deviceId,
//													  System.currentTimeMillis(), "DeviceData",
//													  sonar,
//													  "SONAR");
//
//				if (!result) {
//					response.setStatus(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
//					log.error("Error whilst pushing Sonar data: " + sensorValues);
//				}
//
//			} else {
//				DeviceController deviceController = new DeviceController();
//				result = deviceController.pushBamData(dataMsg.owner, FireAlarmConstants
//															  .DEVICE_TYPE,
//													  dataMsg.deviceId,
//													  System.currentTimeMillis(), "DeviceData",
//													  dataMsg.value, dataMsg.reply);
//				if (!result) {
//					response.setStatus(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
//					log.error("Error whilst pushing sensor data: " + sensorValues);
//				}
//			}
//
//		} catch (UnauthorizedException e) {
//			response.setStatus(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
//			log.error("Data Push Attempt Failed at Publisher: " + e.getMessage());
//		}
//	}


	private String sendCommandViaXMPP(String deviceOwner, String deviceId, String resource,
									  String state) throws DeviceManagementException {

		String replyMsg = "";
		String scriptArguments = "";
		String command = "";

		String seperator = File.separator;
		String xmppServerURL = XmppConfig.getInstance().getXmppEndpoint();
		int indexOfChar = xmppServerURL.lastIndexOf(seperator);
		if (indexOfChar != -1) {
			xmppServerURL = xmppServerURL.substring((indexOfChar + 1), xmppServerURL.length());
		}

		indexOfChar = xmppServerURL.indexOf(":");
		if (indexOfChar != -1) {
			xmppServerURL = xmppServerURL.substring(0, indexOfChar);
		}

		String xmppAdminUName = XmppConfig.getInstance().getXmppUsername();
		String xmppAdminPass = XmppConfig.getInstance().getXmppPassword();

		String xmppAdminUserLogin = xmppAdminUName + "@" + xmppServerURL + seperator + deviceOwner;
		String clientToConnect = deviceId + "@" + xmppServerURL + seperator + deviceOwner;

		String scriptsFolder = "repository" + seperator + "resources" + seperator + "scripts";
		String scriptPath = CarbonUtils.getCarbonHome() + seperator + scriptsFolder + seperator
				+ "xmpp_client.py ";

		scriptArguments =
				"-j " + xmppAdminUserLogin + " -p " + xmppAdminPass + " -c " + clientToConnect +
						" -r " + resource + " -s " + state;
		command = "python " + scriptPath + scriptArguments;

		if (log.isDebugEnabled()) {
			log.debug("Connecting to XMPP Server via Admin credentials: " + xmppAdminUserLogin);
			log.debug("Trying to contact xmpp device account: " + clientToConnect);
			log.debug("Arguments used for the scripts: '" + scriptArguments + "'");
			log.debug("Command exceuted: '" + command + "'");
		}

//		switch (resource) {
//			case BULB_CONTEXT:
//				scriptArguments = "-r Bulb -s " + state;
//				command = "python " + scriptPath + scriptArguments;
//				break;
//			case SONAR_CONTEXT:
//				scriptArguments = "-r Sonar";
//				command = "python " + scriptPath + scriptArguments;
//				break;
//			case TEMPERATURE_CONTEXT:
//				scriptArguments = "-r Temperature";
//				command = "python " + scriptPath + scriptArguments;
//				break;
//		}

		replyMsg = executeCommand(command);
		return replyMsg;
	}


	private String executeCommand(String command) {
		StringBuffer output = new StringBuffer();

		Process p;
		try {
			p = Runtime.getRuntime().exec(command);
			p.waitFor();
			BufferedReader reader =
					new BufferedReader(new InputStreamReader(p.getInputStream()));

			String line = "";
			while ((line = reader.readLine()) != null) {
				output.append(line + "\n");
			}

		} catch (Exception e) {
			log.info(e.getMessage(), e);
		}

		return output.toString();

	}


	private boolean sendCommandViaMQTT(String deviceOwner, String deviceId, String resource,
									   String state) throws DeviceManagementException {

		boolean result = false;
		DeviceController deviceController = new DeviceController();

		try {
			result = deviceController.publishMqttControl(deviceOwner,
														 FireAlarmConstants.DEVICE_TYPE,
														 deviceId, resource, state);
		} catch (DeviceControllerException e) {
			String errorMsg = "Error whilst trying to publish to MQTT Queue";
			log.error(errorMsg);
			throw new DeviceManagementException(errorMsg, e);
		}
		return result;
	}


	private String sendCommandViaHTTP(final String deviceIp, int deviceServerPort,
									  String callUrlPattern,
									  boolean fireAndForgot)
			throws DeviceManagementException {

		if (deviceServerPort == 0) {
			deviceServerPort = 80;
		}

		String responseMsg = "";
		String urlString = URL_PREFIX + deviceIp + ":" + deviceServerPort + callUrlPattern;

		if (log.isDebugEnabled()) {
			log.debug(urlString);
		}

		if (!fireAndForgot) {
			HttpURLConnection httpConnection = getHttpConnection(urlString);

			try {
				httpConnection.setRequestMethod(HttpMethod.GET);
			} catch (ProtocolException e) {
				String errorMsg =
						"Protocol specific error occurred when trying to set method to GET" +
								" for:" + urlString;
				log.error(errorMsg);
				throw new DeviceManagementException(errorMsg, e);
			}

			responseMsg = readResponseFromGetRequest(httpConnection);

		} else {
			CloseableHttpAsyncClient httpclient = null;
			try {

				httpclient = HttpAsyncClients.createDefault();
				httpclient.start();
				HttpGet request = new HttpGet(urlString);
				final CountDownLatch latch = new CountDownLatch(1);
				Future<HttpResponse> future = httpclient.execute(
						request, new FutureCallback<HttpResponse>() {
							@Override
							public void completed(HttpResponse httpResponse) {
								latch.countDown();
							}

							@Override
							public void failed(Exception e) {
								latch.countDown();
							}

							@Override
							public void cancelled() {
								latch.countDown();
							}
						});

				latch.await();

			} catch (InterruptedException e) {
				if (log.isDebugEnabled()) {
					log.debug("Sync Interrupted");
				}
			} finally {
				try {
					if (httpclient != null) {
						httpclient.close();

					}
				} catch (IOException e) {
					if (log.isDebugEnabled()) {
						log.debug("Failed on close");
					}
				}
			}

		}

		return responseMsg;
	}

    /* Utility methods relevant to creating and sending http requests */

	/* This methods creates and returns a http connection object */

	private HttpURLConnection getHttpConnection(String urlString) throws
																  DeviceManagementException {

		URL connectionUrl = null;
		HttpURLConnection httpConnection = null;

		try {
			connectionUrl = new URL(urlString);
			httpConnection = (HttpURLConnection) connectionUrl.openConnection();
		} catch (MalformedURLException e) {
			String errorMsg =
					"Error occured whilst trying to form HTTP-URL from string: " + urlString;
			log.error(errorMsg);
			throw new DeviceManagementException(errorMsg, e);
		} catch (IOException e) {
			String errorMsg = "Error occured whilst trying to open a connection to: " +
					connectionUrl.toString();
			log.error(errorMsg);
			throw new DeviceManagementException(errorMsg, e);
		}

		return httpConnection;
	}

	/* This methods reads and returns the response from the connection */

	private String readResponseFromGetRequest(HttpURLConnection httpConnection)
			throws DeviceManagementException {
		BufferedReader bufferedReader = null;
		try {
			bufferedReader = new BufferedReader(new InputStreamReader(
					httpConnection.getInputStream()));
		} catch (IOException e) {
			String errorMsg =
					"There is an issue with connecting the reader to the input stream at: " +
							httpConnection.getURL();
			log.error(errorMsg);
			throw new DeviceManagementException(errorMsg, e);
		}

		String responseLine;
		StringBuffer completeResponse = new StringBuffer();

		try {
			while ((responseLine = bufferedReader.readLine()) != null) {
				completeResponse.append(responseLine);
			}
		} catch (IOException e) {
			String errorMsg =
					"Error occured whilst trying read from the connection stream at: " +
							httpConnection.getURL();
			log.error(errorMsg);
			throw new DeviceManagementException(errorMsg, e);
		}
		try {
			bufferedReader.close();
		} catch (IOException e) {
			log.error(
					"Could not succesfully close the bufferedReader to the connection at: " +
							httpConnection.getURL());
		}

		return completeResponse.toString();
	}

}
