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
import org.openeai.moa.EnterpriseObjectGenerateException;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.transport.RequestService;

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import edu.emory.awsaccount.service.provider.AccountDeprovisioningProvider;
import edu.emory.moa.jmsobjects.network.v1_0.VpnConnection;
import edu.emory.moa.jmsobjects.network.v1_0.VpnConnectionDeprovisioning;
import edu.emory.moa.jmsobjects.network.v1_0.VpnConnectionProfileAssignment;
import edu.emory.moa.objects.resources.v1_0.VpnConnectionDeprovisioningQuerySpecification;
import edu.emory.moa.objects.resources.v1_0.VpnConnectionProfileAssignmentQuerySpecification;
import edu.emory.moa.objects.resources.v1_0.VpnConnectionQuerySpecification;

/**
 * Deprovision VPN connections for all VPCs associated with an account.
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 21 May 2017
 **/
public class DeprovisionVpnConnections extends AbstractStep implements Step {

	private ProducerPool m_networkOpsServiceProducerPool = null;
	int m_sleepTimeInMillis = 5000;
	int m_maxWaitTimeInMillis = 600000;
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

		// Get custom step properties.
		logger.info(LOGTAG + "Getting custom step properties...");
		
		String sleepTime = getProperties()
			.getProperty("sleepTimeInMillis", "5000");
		int sleepTimeInMillis = Integer.parseInt(sleepTime);
		setSleepTimeInMillis(sleepTimeInMillis);
		logger.info(LOGTAG + "sleepTimeInMillis is: " + 
			getSleepTimeInMillis());
		
		String maxWaitTime = getProperties()
				.getProperty("maxWaitTimeInMillis", "600000");
			int maxWaitTimeInMillis = Integer.parseInt(maxWaitTime);
			setMaxWaitTimeInMillis(maxWaitTimeInMillis);
			logger.info(LOGTAG + "maxWaitTimeInMillis is: " + 
				getMaxWaitTimeInMillis());
		
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
	
	private String getActualPresharedKey() {
		return null;
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
		
		String LOGTAG = getStepTag() + 
			"[DeprovisionVpnConnections.queryForVpnConnectionProfileAssignment] ";
		
	    // Get a configured VpnConnectionProfileAssignment object and
	    // VpnConnectionProfileAssignmentQuerySpecification object 
		// from AppConfig
	    VpnConnectionProfileAssignment assignment = new 
			VpnConnectionProfileAssignment();
		VpnConnectionProfileAssignmentQuerySpecification querySpec = 
			new VpnConnectionProfileAssignmentQuerySpecification();
	    try {
	    	assignment = (VpnConnectionProfileAssignment)getAppConfig()
		    		.getObjectByType(assignment.getClass().getName());
	    	querySpec = (VpnConnectionProfileAssignmentQuerySpecification)getAppConfig()
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
	    	querySpec.setOwnerId(vpcId);
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
			results = assignment.query(querySpec, rs);
			long queryTime = System.currentTimeMillis() - queryStartTime;
			logger.info(LOGTAG + "Queried for VpnConnectionProfileAssignment" +
				" for ownerId " + vpcId + "in " + queryTime +
				"ms. There are " + results.size() + " result(s).");
		}
		catch (EnterpriseObjectQueryException eoqe) {
			String errMsg = "An error occurred querying for the  " +
	    	  "VpnConnectionProfileAssignment object. The " +
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
			VpnConnectionProfileAssignment result = 
				(VpnConnectionProfileAssignment)results.get(0);
			return result;
		}
		else {
			String errMsg = "Invalid number of results returned from " +
				"VpnConnectionProfileAssignment.Query-Request. " +
				results.size() + " results returned. " +
				"Expected exactly 1.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}	
	}
	
	private VpnConnection queryForVpnConnection(String ownerId) throws StepException {
		
		String LOGTAG = getStepTag() + 
			"[DeprovisionVpnConnections.queryForVpnConnection] ";
		
	    // Get a configured VpnConnection object and query spec from AppConfig
	    VpnConnection connection = new VpnConnection();
		VpnConnectionQuerySpecification querySpec = 
			new VpnConnectionQuerySpecification();
	    try {
	    	connection = (VpnConnection)getAppConfig()
		    	.getObjectByType(connection.getClass().getName());
	    	querySpec = (VpnConnectionQuerySpecification)getAppConfig()
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
	    	querySpec.setVpcId(ownerId);
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
			results = connection.query(querySpec, rs);
			long queryTime = System.currentTimeMillis() - queryStartTime;
			logger.info(LOGTAG + "Queried for the VpnConnection" +
				" for VpcId " + ownerId + "in " + queryTime +
				"ms. There are " + results.size() + " result(s).");
		}
		catch (EnterpriseObjectQueryException eoqe) {
			String errMsg = "An error occurred querying for the  " +
	    	  "VpnConnection object. The exception is: " + eoqe.getMessage();
	    	logger.error(LOGTAG + errMsg);
	    	throw new StepException(errMsg, eoqe);
		}
		finally {
			// Release the producer back to the pool
			getNetworkOpsServiceProducerPool()
				.releaseProducer((MessageProducer)rs);
		}
		
		if (results.size() == 1) {
			VpnConnection result = (VpnConnection)results.get(0);
			return result;
		}
		else {
			String errMsg = "Invalid number of results returned from " +
				"VpnConnection.Query-Request. " + results.size() +
				" results returned. Expected exactly 1.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}	
	}
//TODO	
	private VpnConnectionProfileAssignment 
		getVpnConnectionProfileAssignmentForConnection(List<VpnConnectionProfileAssignment> 
		assignments, VpnConnection vpnc) throws StepException {
		
		VpnConnectionProfileAssignment assignment = new VpnConnectionProfileAssignment();
		return assignment;
	}
	
	private VpnConnectionDeprovisioning 
		deprovisionVpnConnection(VpnConnectionProfileAssignment assignment)
		throws StepException {
		
		String LOGTAG = getStepTag() + 
				"[DeprovisionVpnConnections.deprovisionVpnConnection] ";
			
	    // Get a configured VpnConnectionDeprovisioning object from AppConfig
	    VpnConnectionDeprovisioning dep = new VpnConnectionDeprovisioning();
	    try {
	    	dep = (VpnConnectionDeprovisioning)getAppConfig()
		    	.getObjectByType(dep.getClass().getName());
	    }
	    catch (EnterpriseConfigurationObjectException ecoe) {
	    	String errMsg = "An error occurred retrieving an object from " +
	    	  "AppConfig. The exception is: " + ecoe.getMessage();
	    	logger.error(LOGTAG + errMsg);
	    	throw new StepException(errMsg, ecoe);
	    }
	    
	    // Log the state of the assignment object.
	    try {
	    	logger.info(LOGTAG + "Generate object (VpnConnectionProfileAssignment) is: " 
	    		+ assignment.toXmlString());
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
		
		// Generate the deprovisioning object
		List results = null;
		long generateStartTime = System.currentTimeMillis();
		try { 	
			results = dep.generate(assignment, rs);
			long generateTime = System.currentTimeMillis() - generateStartTime;
			logger.info(LOGTAG + "Generated the VpnConnection Deprovisioning " +
				" for VPC " + assignment.getOwnerId() + " and profile ID " +
				assignment.getVpnConnectionProfileId() + " in " + generateTime +
				"ms. There are " + results.size() + " result(s).");
		}
		catch (EnterpriseObjectGenerateException eoge) {
			String errMsg = "An error occurred generating the  " +
	    	  "VpnConnectionDeprovisioning object. The exception is: "
	    	  + eoge.getMessage();
	    	logger.error(LOGTAG + errMsg);
	    	throw new StepException(errMsg, eoge);
		}
		finally {
			// Release the producer back to the pool
			getNetworkOpsServiceProducerPool()
				.releaseProducer((MessageProducer)rs);
		}
		if (results.size() == 1) {
			dep = (VpnConnectionDeprovisioning)results.get(0);
			logger.info(LOGTAG + "The VpnConnectionDeprovisioning ID is: " +
				dep.getProvisioningId());
		}
		else {
			String errMsg = "Invalid number of results returned from " +
				"VpnConnectionDeprovisioning.Generate-Request. " + results.size() +
				" results returned. Expected exactly 1.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}	
		
		// While the deprovisioning time is less that the maxWaitTime,
		// query for the VpnConnectionDeprovisioning object and
		// evaluate it for success or failure.
		while (System.currentTimeMillis() - generateStartTime < getMaxWaitTimeInMillis()) {

			// Sleep for the sleep interval.
			logger.info(LOGTAG + "Sleeping for " + getSleepTimeInMillis() +
					" prior to next VpnConnectionDeprovisioning query.");
			try {
				Thread.sleep(getSleepTimeInMillis());
			} catch (InterruptedException ie) {
				String errMsg = "Error occurred sleeping.";
				logger.error(LOGTAG + errMsg + ie.getMessage());
				throw new StepException(errMsg, ie);
			}

			// Query for the VpnConnectionDeprovisioning object.
			dep = queryForVpnDeprovisioning(dep.getProvisioningId());

			// If the VpnConnectionDeprovisioning is successful, log it,
			// and set result properties.
			if (isSuccess(dep)) {
				logger.info(LOGTAG + "VPN connection deprovisioning successful for " +
					"VPN " + assignment.getOwnerId() + " and VPN connection profile ID " +
					assignment.getVpnConnectionProfileAssignmentId());
				break;
			} 
			else if (isFailure(dep)) {
				logger.info(LOGTAG + "VPN connection deprovisioning successful for " +
					"VPN " + assignment.getOwnerId() + " and VPN connection profile ID " +
					assignment.getVpnConnectionProfileAssignmentId());
				break;
			}
		}
		
		return dep;
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
	    	  "VpnConnectionProvisioning object. The " +
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
				"VpnConnectionDeprovisioning.Query-Request. " +
				results.size() + " results returned. " +
				"Expected exactly 1.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}	
	}
	
	private boolean isSuccess(VpnConnectionDeprovisioning dep) {
		
		if (dep.getProvisioningResult() != null) {
			if (dep.getProvisioningResult().equalsIgnoreCase(SUCCESS_RESULT)) {
				return true;
			}
			else return false;
		}
		
		else return false;
	}
	
	private boolean isFailure(VpnConnectionDeprovisioning dep) {
		
		if (dep.getProvisioningResult() != null) {
			if (dep.getProvisioningResult().equalsIgnoreCase(FAILURE_RESULT)) {
				return true;
			}
			else return false;
		}
		else return false;
	}
	
	private void setSleepTimeInMillis(int time) {
		m_sleepTimeInMillis = time;
	}
	
	private int getSleepTimeInMillis() {
		return m_sleepTimeInMillis;
	}
	
	private void setMaxWaitTimeInMillis(int time) {
		m_maxWaitTimeInMillis = time;
	}
	
	private int getMaxWaitTimeInMillis() {
		return m_maxWaitTimeInMillis;
	}
}
