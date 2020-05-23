/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the RHEDcloud AWS Account Service.

 Copyright (C) 2020 RHEDcloud Foundation. All rights reserved. 
 ******************************************************************************/
package edu.emory.awsaccount.service.deprovisioning.step;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ListIterator;
import java.util.Properties;

import javax.jms.JMSException;

import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.PointToPointProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.transport.RequestService;

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import edu.emory.awsaccount.service.provider.AccountDeprovisioningProvider;
import edu.emory.moa.jmsobjects.network.v1_0.VpnConnection;
import edu.emory.moa.jmsobjects.network.v1_0.VpnConnectionDeprovisioning;
import edu.emory.moa.jmsobjects.network.v1_0.VpnConnectionProfileAssignment;
import edu.emory.moa.objects.resources.v1_0.VpnConnectionDeprovisioningQuerySpecification;

/**
 * Deprovision VPN connections for all VPCs associated with an account.
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 21 May 2017
 **/
public class DeprovisionVpnConnections extends AbstractStep implements Step {

	private ProducerPool m_networkOpsServiceProducerPool = null;
	private String m_remoteVpnIpAddressForTesting = null;
	private String m_presharedKeyTemplateForTesting = null;
	private String m_vpnConnectionProfileId = null;
	private int m_requestTimeoutIntervalInMillis = 600000;
	
	public void init (String provisioningId, Properties props, 
			AppConfig aConfig, AccountDeprovisioningProvider adp) 
			throws StepException {
		
		super.init(provisioningId, props, aConfig, adp);
		
		String LOGTAG = getStepTag() + "[DeprovisionVpnConnections.init] ";
		
		// This step needs to send messages to the Network Ops Service
		// to deprovision the VPN connection.
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
		
		String requestTimeoutInterval = getProperties()
			.getProperty("requestTimeoutIntervalInMillis", "600000");
		int requestTimeoutIntervalInMillis = Integer.parseInt(requestTimeoutInterval);
		setRequestTimeoutIntervalInMillis(requestTimeoutIntervalInMillis);
		logger.info(LOGTAG + "requestTimeoutIntervalInMillis is: " + 
			getRequestTimeoutIntervalInMillis());
		
		logger.info(LOGTAG + "Initialization complete.");
	}
	
	protected List<Property> run() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[DeprovisionVpnConnections.run] ";
		logger.info(LOGTAG + "Begin running the step.");

		
		// Get the VpcIds property from a previous step.
		String vpcIds =
				getStepPropertyValue("LIST_VPC_IDS", "VpcIds");
		
		// If there are no VPCs there is nothing to do and the step is complete.
		if (vpcIds == null || vpcIds.equalsIgnoreCase("none")) {
			logger.info(LOGTAG + "There are no VPCs.");
			// Update the step.
			update(COMPLETED_STATUS, SUCCESS_RESULT);
	    	
	    	// Log completion time.
	    	long time = System.currentTimeMillis() - startTime;
	    	logger.info(LOGTAG + "Step run completed in " + time + "ms.");
	    	
	    	// Return the properties.
	    	return getResultProperties();
		}
		
		// Get the VPCs as a list.
		List<String> vpcList = Arrays.asList(vpcIds.split("\\s*,\\s*"));
		logger.info(LOGTAG + "There are " + vpcList.size() + " VPCs.");
		
		// If there are no VPCs in the list there is nothing to do and the 
		// step is complete.
		if (vpcList.size() == 0) {
			logger.info(LOGTAG + "There are no VPCs.");
			// Update the step.
			update(COMPLETED_STATUS, SUCCESS_RESULT);
	    	
	    	// Log completion time.
	    	long time = System.currentTimeMillis() - startTime;
	    	logger.info(LOGTAG + "Step run completed in " + time + "ms.");
	    	
	    	// Return the properties.
	    	return getResultProperties();
		}
		
		// For each VPC, query for a VPN connection profile assignment.
		List<VpnConnectionProfileAssignment> vpnConnectionProfileAssignments = 
			new ArrayList<VpnConnectionProfileAssignment>();
		ListIterator<String> li = vpcList.listIterator();
		while (li.hasNext()) {
			String vpcId = li.next();
			VpnConnectionProfileAssignment vcpa = queryForVpnConnectionProfileAssignment(vpcId);
			if (vcpa != null) {
				vpnConnectionProfileAssignments.add(vcpa);
			}
		}
		logger.info(LOGTAG + "There are " + vpnConnectionProfileAssignments.size() + 
			" VpnConnectionProfileAssignments to check for VpnConnections.");
		
		// If there are no VpnConnectionProfileAssignments to check, there is nothing
		// to do and the step is complete.
		if (vpnConnectionProfileAssignments.size() == 0) {
			logger.info(LOGTAG + "There are no VpnConnectionProfileAssignments to check. " +
				"There is nothing to do.");
			// Update the step.
			update(COMPLETED_STATUS, SUCCESS_RESULT);
	    	
	    	// Log completion time.
	    	long time = System.currentTimeMillis() - startTime;
	    	logger.info(LOGTAG + "Step run completed in " + time + "ms.");
	    	
	    	// Return the properties.
	    	return getResultProperties();
		}
		
		// For each VpnConnectionProfileAssignment, query for a site-to-site VPN
		// connection on the routers.
		List<VpnConnection> vpnConnections = new ArrayList<VpnConnection>();
		ListIterator<VpnConnectionProfileAssignment> vpncpai = 
			vpnConnectionProfileAssignments.listIterator();
		while (vpncpai.hasNext()) {
			VpnConnectionProfileAssignment vpncpa = vpncpai.next();
			String ownerId = vpncpa.getOwnerId();
			VpnConnection vpnc = queryForVpnConnection(ownerId);
			if (vpnc != null) {
				vpnConnections.add(vpnc);
			}
		}
		if (vpnConnections.size() == 0) {
			logger.info(LOGTAG + "There are no VpnConnections to deprovision. " +
				"There is nothing more to do.");
			// Update the step.
			update(COMPLETED_STATUS, SUCCESS_RESULT);
	    	
	    	// Log completion time.
	    	long time = System.currentTimeMillis() - startTime;
	    	logger.info(LOGTAG + "Step run completed in " + time + "ms.");
	    	
	    	// Return the properties.
	    	return getResultProperties();
		}
		if (vpnConnections.size() == 1) {
			logger.info(LOGTAG + "There is 1 VpnConnection to deprovision.");
		}
		else {
			logger.info(LOGTAG + "There are " + vpnConnections.size() + 
				" VpnConnections to deprovision.");
		}
			
		// Deprovision each VpnConnection in the list.
		ListIterator<VpnConnection> vcli = vpnConnections.listIterator();
		int vpnCount = 1;
		while (vcli.hasNext()) {
			VpnConnection vpnc = vcli.next();
			VpnConnectionProfileAssignment vcpa = 
					getVpnConnectionProfileAssignmentForConnection(vpnConnectionProfileAssignments, vpnc);
			try {
				deprovisionVpnConnection(vcpa);
				String msg = "Successfully deprovisioned a VPN connection for " +
						"VpcId " + vcpa.getOwnerId() + " and VpnConnectionProfileId " +
						vcpa.getVpnConnectionProfileId() + ".";
				logger.info(LOGTAG + msg);
				addResultProperty("vpnStatus" + vpnCount, msg);
				vpnCount++;
			}
			catch (StepException se) {
				String errMsg = "An error occurred deprovisioning a VPN connection for " +
					"VpcId " + vcpa.getOwnerId() + " and VpnConnectionProfileId " +
					vcpa.getVpnConnectionProfileId() + ". The exception is: " + 
					se.getMessage();
				logger.info(LOGTAG + errMsg);
				addResultProperty("vpnStatus" + vpnCount, errMsg);
			}
		}
		
		// The step is done. Update the step.
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
	
	private void setRequestTimeoutIntervalInMillis(int time) {
		m_requestTimeoutIntervalInMillis = time;
	}
	
	private int getRequestTimeoutIntervalInMillis() {
		return m_requestTimeoutIntervalInMillis;
	}
	
	private VpnConnectionProfileAssignment queryForVpnConnectionProfileAssignment(String vpcId) 
		throws StepException {
		
		VpnConnectionProfileAssignment vcpa = new VpnConnectionProfileAssignment();
		return vcpa;
		
	}
	
	private VpnConnection queryForVpnConnection(String ownerId) throws StepException {
		
		VpnConnection vpnc = new VpnConnection();
		return vpnc;
	}
	
	private VpnConnectionProfileAssignment 
		getVpnConnectionProfileAssignmentForConnection(List<VpnConnectionProfileAssignment> 
		assignments, VpnConnection vpnc) throws StepException {
		
		VpnConnectionProfileAssignment assignment = new VpnConnectionProfileAssignment();
		return assignment;
	}
	
	private void deprovisionVpnConnection(VpnConnectionProfileAssignment assignment)
		throws StepException {
		
		return;
	}
	
	private VpnConnectionDeprovisioning queryForVpnDeprovisioning(String deprovisioningId)
			throws StepException {
			
		String LOGTAG = getStepTag() + "[DeprovisionVpnConnections.queryForVpnDeprovisioning] ";
		
	    // Get a configured VpnConnectionDeprovisioning object and
	    // VpnConnectionDeprovisioningQuerySpecification object from AppConfig
	    VpnConnectionDeprovisioning vpnDeprovisioning = new 
			VpnConnectionDeprovisioning();
		VpnConnectionDeprovisioningQuerySpecification querySpec = 
			new VpnConnectionDeprovisioningQuerySpecification();
	    try {
	    	vpnDeprovisioning = (VpnConnectionDeprovisioning)getAppConfig()
		    		.getObjectByType(vpnDeprovisioning.getClass().getName());
	    	querySpec = (VpnConnectionDeprovisioningQuerySpecification)getAppConfig()
		    		.getObjectByType(querySpec.getClass().getName());
	    }
	    catch (EnterpriseConfigurationObjectException ecoe) {
	    	String errMsg = "An error occurred retrieving an object from " +
	    	  "AppConfig. The exception is: " + ecoe.getMessage();
	    	logger.error(LOGTAG + errMsg);
	    	throw new StepException(errMsg, ecoe);
	    }
		
	    // Set the values of the query spec.
	    try {
	    	querySpec.setProvisioningId(deprovisioningId);
	    }
	    catch (EnterpriseFieldException efe) {
	    	String errMsg = "An error occurred setting the values of the " +
	  	    	  "object. The exception is: " + efe.getMessage();
	  	    logger.error(LOGTAG + errMsg);
	  	    throw new StepException(errMsg, efe);
	    }
	    
	    // Log the state of the object.
	    try {
	    	logger.info(LOGTAG + "query spec is: " 
	    		+ querySpec.toXmlString());
	    }
	    catch (XmlEnterpriseObjectException xeoe) {
	    	String errMsg = "An error occurred serializing the " +
	  	    	  "object to XML. The exception is: " + xeoe.getMessage();
  	    	logger.error(LOGTAG + errMsg);
  	    	throw new StepException(errMsg, xeoe);
	    }    
		
		// Get a producer from the pool
		RequestService rs = null;
		try {
			PointToPointProducer p2p = 
				(PointToPointProducer)getNetworkOpsServiceProducerPool()
				.getExclusiveProducer();
			p2p.setRequestTimeoutInterval(getRequestTimeoutIntervalInMillis());
			rs = (RequestService)p2p;
		}
		catch (JMSException jmse) {
			String errMsg = "An error occurred getting a producer " +
				"from the pool. The exception is: " + jmse.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg, jmse);
		}
	    
		List results = null;
		try { 
			long queryStartTime = System.currentTimeMillis();
			results = vpnDeprovisioning.query(querySpec, rs);
			long queryTime = System.currentTimeMillis() - queryStartTime;
			logger.info(LOGTAG + "Queried for VpnConnectionDeprovisioning" +
				" with ProvisioningId " + deprovisioningId + "in " + queryTime +
				"ms. There are " + results.size() + " result(s).");
		}
		catch (EnterpriseObjectQueryException eoqe) {
			String errMsg = "An error occurred querying for the  " +
	    	  "VpnConnectionProvisinoing object. The " +
	    	  "exception is: " + eoqe.getMessage();
	    	logger.error(LOGTAG + errMsg);
	    	throw new StepException(errMsg, eoqe);
		}
		finally {
			// Release the producer back to the pool
			getNetworkOpsServiceProducerPool()
				.releaseProducer((MessageProducer)rs);
		}
		
		if (results.size() == 1) {
			VpnConnectionDeprovisioning dep = 
				(VpnConnectionDeprovisioning)results.get(0);
			return dep;
		}
		else {
			String errMsg = "Invalid number of results returned from " +
				"VpnConnectionDeProvisioning.Query-Request. " +
				results.size() + " results returned. " +
				"Expected exactly 1.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}	
	}
}
