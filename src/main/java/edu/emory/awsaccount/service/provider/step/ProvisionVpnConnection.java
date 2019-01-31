/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2017 Emory University. All rights reserved. 
 ******************************************************************************/
package edu.emory.awsaccount.service.provider.step;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javax.jms.JMSException;

import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectGenerateException;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.EnterpriseObjectUpdateException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.transport.RequestService;

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudRequisition;

import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;
import edu.emory.moa.jmsobjects.network.v1_0.VpnConnectionProfile;
import edu.emory.moa.jmsobjects.network.v1_0.VpnConnectionProfileAssignment;
import edu.emory.moa.jmsobjects.network.v1_0.VpnConnectionProvisioning;
import edu.emory.moa.objects.resources.v1_0.RemoteVpnConnectionInfo;
import edu.emory.moa.objects.resources.v1_0.RemoteVpnTunnel;
import edu.emory.moa.objects.resources.v1_0.VpnConnectionProfileAssignmentQuerySpecification;
import edu.emory.moa.objects.resources.v1_0.VpnConnectionProfileQuerySpecification;
import edu.emory.moa.objects.resources.v1_0.VpnConnectionRequisition;

/**
 * Example step that can serve as a placholder.
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 21 May 2017
 **/
public class ProvisionVpnConnection extends AbstractStep implements Step {

	private ProducerPool m_networkOpsServiceProducerPool = null;
	private String m_remoteVpnIpAddressForTesting = null;
	private String m_presharedKeyTemplateForTesting = null;
	private String m_vpnConnectionProfileId = null;
	
	public void init (String provisioningId, Properties props, 
			AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp) 
			throws StepException {
		
		super.init(provisioningId, props, aConfig, vpcpp);
		
		String LOGTAG = getStepTag() + "[ProvisionVpnConnection.init] ";
		
		// This step needs to send messages to the Network Ops Service
		// to provision or deprovision the VPN connection.
		ProducerPool p2p1 = null;
		try {
			p2p1 = (ProducerPool)getAppConfig()
				.getObject("NetworkOpsServiceProducerPool");
			setNetworkOpsServiceProducerPool(p2p1);
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			// An error occurred retrieving an object from AppConfig. Log it and
			// throw an exception.
			String errMsg = "An error occurred retrieving an object from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
			logger.error(LOGTAG + errMsg);
			addResultProperty("errorMessage", errMsg);
			throw new StepException(errMsg);
		}
		
		logger.info(LOGTAG + "Getting custom step properties...");
		String remoteVpnIpAddressForTesting = getProperties()
				.getProperty("remoteVpnIpAddressForTesting", null);
		setRemoteVpnIpAddressForTesting(remoteVpnIpAddressForTesting);
		logger.info(LOGTAG + "remoteVpnIpAddressForTesting is: " + 
				getRemoteVpnIpAddressForTesting());
		
		String presharedKeyTemplateForTesting = getProperties()
				.getProperty("presharedKeyTemplateForTesting", null);
		setPresharedKeyTemplateForTesting(presharedKeyTemplateForTesting);
		logger.info(LOGTAG + "presharedKeyTemplateForTesting is: " + 
				getPresharedKeyTemplateForTesting());
		
		logger.info(LOGTAG + "Initialization complete.");
	}
	
	protected List<Property> run() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[ProvisionVpnConnection.run] ";
		logger.info(LOGTAG + "Begin running the step.");
		
		// Get the VpcId property from a previous step.
		String vpcId = 
			getStepPropertyValue("CREATE_VPC_TYPE1_CFN_STACK", "VpcId");
		String vpnConnectionProfileId = 
			getStepPropertyValue("UPDATE_VPN_CONNECTION_ASSIGNMENT", 
			"vpnConnectionProfileId");
		setVpnConnectionProfileId(vpnConnectionProfileId);
		String remoteVpnConnectionId1 = 
			getStepPropertyValue("CREATE_VPC_TYPE1_CFN_STACK", "Vpn1ConnectionId");
		String vpnInsideIpCidr1 = 
			getStepPropertyValue("CREATE_VPC_TYPE1_CFN_STACK", "vpn1InsideTunnelCidr1");
		String remoteVpnConnectionId2 = 
			getStepPropertyValue("CREATE_VPC_TYPE1_CFN_STACK", "Vpn2ConnectionId");
		String vpnInsideIpCidr2 = 
			getStepPropertyValue("CREATE_VPC_TYPE1_CFN_STACK", "vpn2InsideTunnelCidr1");
		String remoteVpnIpAddress1 = getStepPropertyValue("QUERY_FOR_VPN_CONFIGURATION",
			"remoteVpnIpAddress1");
		String remoteVpnIpAddress2 = getStepPropertyValue("QUERY_FOR_VPN_CONFIGURATION",
			"remoteVpnIpAddress2");
		String presharedKey1 = getStepPropertyValue("QUERY_FOR_VPN_CONFIGURATION",
			"presharedKey1");
		String presharedKey2 = getStepPropertyValue("QUERY_FOR_VPN_CONFIGURATION",
			"presharedKey2");
		
		// Compute the local tunnel ids
		int tunnelId1 = 10000 + Integer.parseInt(vpnConnectionProfileId);
		String localTunnelId1 = Integer.toString(tunnelId1);
		int tunnelId2 = 20000 + Integer.parseInt(vpnConnectionProfileId);
		String localTunnelId2 = Integer.toString(tunnelId2);
		
		// Get a configured VpnConnectionProfile and
		// VpnConnectionProfileQuery from AppConfig
		VpnConnectionProfile vpnConnectionProfile = new 
			VpnConnectionProfile();
		VpnConnectionProfileQuerySpecification querySpec = 
			new VpnConnectionProfileQuerySpecification();
	    try {
	    	vpnConnectionProfile = (VpnConnectionProfile)getAppConfig()
		    		.getObjectByType(vpnConnectionProfile.getClass().getName());
	    	querySpec = (VpnConnectionProfileQuerySpecification)getAppConfig()
		    		.getObjectByType(querySpec.getClass().getName());
	    }
	    catch (EnterpriseConfigurationObjectException ecoe) {
	    	String errMsg = "An error occurred retrieving an object from " +
	    	  "AppConfig. The exception is: " + ecoe.getMessage();
	    	logger.error(LOGTAG + errMsg);
	    	throw new StepException(errMsg, ecoe);
	    }
    
	    String provisioningId = getVirtualPrivateCloudProvisioning()
	    		.getProvisioningId();
	    
	    // Set the values of the querySpec.
	    try {
	    	querySpec.setVpnConnectionProfileId(vpnConnectionProfileId);
	    }
	    catch (EnterpriseFieldException efe) {
	    	String errMsg = "An error occurred setting the values of the " +
	  	    	  "requisition. The exception is: " + efe.getMessage();
	  	    logger.error(LOGTAG + errMsg);
	  	    throw new StepException(errMsg, efe);
	    }
	    
	    // Log the state of the querySpec.
	    try {
	    	logger.info(LOGTAG + "querySpec is: " + querySpec.toXmlString());
	    }
	    catch (XmlEnterpriseObjectException xeoe) {
	    	String errMsg = "An error occurred serializing the querySpec " +
	  	    	  "to XML. The exception is: " + xeoe.getMessage();
  	    	logger.error(LOGTAG + errMsg);
  	    	throw new StepException(errMsg, xeoe);
	    }    
		
		// Get a producer from the pool
		RequestService rs = null;
		try {
			rs = (RequestService)getNetworkOpsServiceProducerPool()
				.getExclusiveProducer();
		}
		catch (JMSException jmse) {
			String errMsg = "An error occurred getting a producer " +
				"from the pool. The exception is: " + jmse.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg, jmse);
		}
	    
		List profileResults = null;
		try { 
			long queryStartTime = System.currentTimeMillis();
			profileResults = vpnConnectionProfile.query(querySpec, rs);
			long queryTime = System.currentTimeMillis() - queryStartTime;
			logger.info(LOGTAG + "Queried for VpnConnectionProfile " +
				"for VpnConnectionProfileId " + vpnConnectionProfileId + " in "
				+ queryTime + " ms. Returned " + profileResults.size() + 
				" result(s).");
		}
		catch (EnterpriseObjectQueryException eoqe) {
			String errMsg = "An error occurred querying for the  " +
	    	  "VpnConnectionProfile object. " +
	    	  "The exception is: " + eoqe.getMessage();
	    	logger.error(LOGTAG + errMsg);
	    	throw new StepException(errMsg, eoqe);
		}
		finally {
			// Release the producer back to the pool
			getNetworkOpsServiceProducerPool()
				.releaseProducer((MessageProducer)rs);
		}
		
		// If there is exactly one result, provision the VPN connection.
		if (profileResults.size() == 1) {
			vpnConnectionProfile = (VpnConnectionProfile)profileResults.get(0);
			
			// Log the state of the object.
		    try {
		    	logger.info(LOGTAG + "VpnConnectionProfile returned is: "
		    		+ vpnConnectionProfile.toXmlString());
		    }
		    catch (XmlEnterpriseObjectException xeoe) {
		    	String errMsg = "An error occurred serializing the object " +
		  	    	  "to XML. The exception is: " + xeoe.getMessage();
	  	    	logger.error(LOGTAG + errMsg);
	  	    	throw new StepException(errMsg, xeoe);
		    }    
			
		    // Get a configured VpnConnectionProvisioning object and
		    // VpnConnectionRequisition object from AppConfig
		    VpnConnectionProvisioning vpnProvisioning = new 
				VpnConnectionProvisioning();
			VpnConnectionRequisition vpnReq = 
				new VpnConnectionRequisition();
		    try {
		    	vpnProvisioning = (VpnConnectionProvisioning)getAppConfig()
			    		.getObjectByType(vpnProvisioning.getClass().getName());
		    	vpnReq = (VpnConnectionRequisition)getAppConfig()
			    		.getObjectByType(vpnReq.getClass().getName());
		    }
		    catch (EnterpriseConfigurationObjectException ecoe) {
		    	String errMsg = "An error occurred retrieving an object from " +
		    	  "AppConfig. The exception is: " + ecoe.getMessage();
		    	logger.error(LOGTAG + errMsg);
		    	throw new StepException(errMsg, ecoe);
		    }
			
		    // Set the values of the VpnConnectionRequisition.
		    try {
		    	vpnReq.setVpnConnectionProfile(vpnConnectionProfile);
		    	vpnReq.setOwnerId(vpcId);
		    	
		    	RemoteVpnConnectionInfo rvci1 = 
		    		vpnReq.newRemoteVpnConnectionInfo();
		    	rvci1.setRemoteVpnConnectionId(remoteVpnConnectionId1);
		    	RemoteVpnTunnel rvt1 = rvci1.newRemoteVpnTunnel();
		    	rvt1.setVpnInsideIpCidr(vpnInsideIpCidr1);
		    	rvt1.setRemoteVpnIpAddress(remoteVpnIpAddress1);
		    	rvt1.setPresharedKey(presharedKey1);
		    	rvt1.setLocalTunnelId(localTunnelId1);
		    	rvci1.addRemoteVpnTunnel(rvt1);
		    	vpnReq.addRemoteVpnConnectionInfo(rvci1);
		    	
		    	RemoteVpnConnectionInfo rvci2 = 
		    		vpnReq.newRemoteVpnConnectionInfo();
		    	rvci2.setRemoteVpnConnectionId(remoteVpnConnectionId2);
		    	RemoteVpnTunnel rvt2 = rvci2.newRemoteVpnTunnel();
		    	rvt2.setVpnInsideIpCidr(vpnInsideIpCidr2);
		    	rvt2.setRemoteVpnIpAddress(remoteVpnIpAddress2);
		    	rvt2.setPresharedKey(presharedKey2);
		    	rvt2.setLocalTunnelId(localTunnelId2);
		    	rvci2.addRemoteVpnTunnel(rvt2);
		    	vpnReq.addRemoteVpnConnectionInfo(rvci2);
	    	
		    }
		    catch (EnterpriseFieldException efe) {
		    	String errMsg = "An error occurred setting the values of the " +
		  	    	  "object. The exception is: " + efe.getMessage();
		  	    logger.error(LOGTAG + errMsg);
		  	    throw new StepException(errMsg, efe);
		    }
		    
		    // Log the state of the object.
		    try {
		    	logger.info(LOGTAG + "updated VpnConnectionRequisition: " 
		    		+ vpnReq.toXmlString());
		    }
		    catch (XmlEnterpriseObjectException xeoe) {
		    	String errMsg = "An error occurred serializing the " +
		  	    	  "object to XML. The exception is: " + xeoe.getMessage();
	  	    	logger.error(LOGTAG + errMsg);
	  	    	throw new StepException(errMsg, xeoe);
		    }    
			
			// Get a producer from the pool
			rs = null;
			try {
				rs = (RequestService)getNetworkOpsServiceProducerPool()
					.getExclusiveProducer();
			}
			catch (JMSException jmse) {
				String errMsg = "An error occurred getting a producer " +
					"from the pool. The exception is: " + jmse.getMessage();
				logger.error(LOGTAG + errMsg);
				throw new StepException(errMsg, jmse);
			}
		    
			List results = null;
			try { 
				long generateStartTime = System.currentTimeMillis();
				results = vpnProvisioning.generate(vpnReq, rs);
				long generateTime = System.currentTimeMillis() - generateStartTime;
				logger.info(LOGTAG + "Generate VpnConnectionProvisioning" +
					" in " + generateTime + " ms.");
			}
			catch (EnterpriseObjectGenerateException eoge) {
				String errMsg = "An error occurred generating the  " +
		    	  "VpnConnectionProvisinoing object. The " +
		    	  "exception is: " + eoge.getMessage();
		    	logger.error(LOGTAG + errMsg);
		    	throw new StepException(errMsg, eoge);
			}
			finally {
				// Release the producer back to the pool
				getNetworkOpsServiceProducerPool()
					.releaseProducer((MessageProducer)rs);
			}
			
			if (results.size() == 1) {
				VpnConnectionProvisioning vcp = 
					(VpnConnectionProvisioning)results.get(0);
				// Add result properties
				addResultProperty("generatedVpnConnectionProvisioning", 
						"true");
				addResultProperty("vpnConnectionProvisioningId", 
					vcp.getProvisioningId());
		    	addResultProperty("vpnConnectionProfile", 
		    		vpnConnectionProfileId);
		    	addResultProperty("ownerId", vpcId);
				addResultProperty("remoteVpnConnectionId1", 
						remoteVpnConnectionId1);
				addResultProperty("vpnInsideIpCidr1", 
						vpnInsideIpCidr1);
				addResultProperty("remoteVpnIpAddress1", 
						remoteVpnIpAddress1);
				addResultProperty("presharedKey1", 
						presharedKey1);
				addResultProperty("localTunnelId1", 
						localTunnelId1);
				addResultProperty("remoteVpnConnectionId2", 
						remoteVpnConnectionId2);
				addResultProperty("vpnInsideIpCidr2", 
						vpnInsideIpCidr2);
				addResultProperty("remoteVpnIpAddress2", 
						remoteVpnIpAddress2);
				addResultProperty("presharedKey2", 
						presharedKey2);
				addResultProperty("localTunnelId2", 
						localTunnelId2);		
			}
			else {
				String errMsg = "Invalid number of results returned from " +
					"VpnConnectionProvisioning.Generate-Request. " +
					results.size() + " results returned. " +
					"Expected exactly 1.";
				logger.error(LOGTAG + errMsg);
				throw new StepException(errMsg);
			}
			
		}
	    // If there is not exactly one assignment returned, log it and 
		// throw an exception.
		else {
			String errMsg = "Invalid number of results returned from " +
				"VpnConnectionProfile.Query-Request. " +
				profileResults.size() + " results returned. " +
				"Expected exactly 1.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		
		// Update the step.
		update(COMPLETED_STATUS, SUCCESS_RESULT);
    	
    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step run completed in " + time + "ms.");
    	
    	// Return the properties.
    	return getResultProperties();
    	
	}
	
	protected List<Property> simulate() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[ProvisionVpnConnection.simulate] ";
		logger.info(LOGTAG + "Begin step simulation.");
		
		// Set return properties.
    	addResultProperty("stepExecutionMethod", SIMULATED_EXEC_TYPE);
		
		// Update the step.
    	update(COMPLETED_STATUS, SUCCESS_RESULT);
    	
    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step simulation completed in " + time + "ms.");
    	
    	// Return the properties.
    	return getResultProperties();
	}
	
	protected List<Property> fail() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[ProvisionVpnConnection.fail] ";
		logger.info(LOGTAG + "Begin step failure simulation.");
		
		// Set return properties.
    	addResultProperty("stepExecutionMethod", FAILURE_EXEC_TYPE);
		
		// Update the step.
    	update(COMPLETED_STATUS, FAILURE_RESULT);
    	
    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step failure simulation completed in " + time + "ms.");
    	
    	// Return the properties.
    	return getResultProperties();
	}
	
	public void rollback() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[ProvisiongVpnConnection.rollback] ";
		
// TODO: Implement deprovisioning
		
		update(ROLLBACK_STATUS, SUCCESS_RESULT);
		
		// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
	}
	
	private void setNetworkOpsServiceProducerPool(ProducerPool pool) {
		m_networkOpsServiceProducerPool = pool;
	}
	
	private ProducerPool getNetworkOpsServiceProducerPool() {
		return m_networkOpsServiceProducerPool;
	}

	private void setRemoteVpnIpAddressForTesting(String ipAddress)  
		throws StepException {
	
		m_remoteVpnIpAddressForTesting = ipAddress;
	}
	
	private String getRemoteVpnIpAddressForTesting() {
		return m_remoteVpnIpAddressForTesting;
	}
	
	private void setPresharedKeyTemplateForTesting(String template)  
		throws StepException {
	
		m_presharedKeyTemplateForTesting = template;
	}
		
	private String getPresharedKeyTemplateForTesting() {
		return m_presharedKeyTemplateForTesting;
	}
	
	private void setVpnConnectionProfileId(String id) {
		m_vpnConnectionProfileId = id;
	}
	
	private String getVpnConnectionProfileId() {
		return m_vpnConnectionProfileId;
	}
	
	private String getPresharedKey() {
		
		String LOGTAG = getStepTag() + "[ProvisionVpnConnection.getPresharedKey] ";
		String key = null;
		String keyPrefix = getPresharedKeyTemplateForTesting();
		String keySuffix = null;
		int vpnConnectionProfileId = Integer.parseInt(getVpnConnectionProfileId());
		if (getActualPresharedKey() == null) {
			logger.info(LOGTAG + "Formatting " + vpnConnectionProfileId + 
				" as three padded characters...");
			keySuffix =	String.format("%03d", vpnConnectionProfileId);
			logger.info(LOGTAG + "keySuffix is: " + keySuffix);
			key = keyPrefix + keySuffix;
			logger.info(LOGTAG + "key is: " + key);
		}
		else {
			key = getActualPresharedKey();
		}
		return key;	
	}
	
	private String getActualPresharedKey() {
		return null;
	}
	
	private String getRemoteVpnIpAddress() {
		
		String ip = null;
		if (getActualRemoteVpnIpAddress() == null) {
			ip = getRemoteVpnIpAddressForTesting();
		}
		else {
			ip = getActualRemoteVpnIpAddress();
		}
		return ip;	
	}
	
	private String getActualRemoteVpnIpAddress() {
		return null;
	}
	
}