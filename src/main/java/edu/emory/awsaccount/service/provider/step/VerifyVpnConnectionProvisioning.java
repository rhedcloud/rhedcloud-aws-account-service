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
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.transport.RequestService;

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudRequisition;

import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;
import edu.emory.moa.jmsobjects.network.v1_0.VpnConnectionProvisioning;
import edu.emory.moa.objects.resources.v1_0.ProvisioningStep;
import edu.emory.moa.objects.resources.v1_0.VpnConnectionProvisioningQuerySpecification;
import edu.emory.moa.objects.resources.v1_0.VpnConnectionRequisition;

/**
 * Example step that can serve as a placholder.
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 21 May 2017
 **/
public class VerifyVpnConnectionProvisioning extends AbstractStep implements Step {
	
	int m_sleepTimeInMillis = 5000;
	int m_maxWaitTimeInMillis = 600000;
	int m_requestTimeoutIntervalInMillis = 10000;
	private ProducerPool m_networkOpsServiceProducerPool = null;

	public void init (String provisioningId, Properties props, 
			AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp) 
			throws StepException {
		
		super.init(provisioningId, props, aConfig, vpcpp);
		
		String LOGTAG = getStepTag() + "[VerifyVpnConnectionProvisioning.init] ";
		
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
				.getProperty("requestTimeoutIntervalInMillis", "10000");
			int requestTimeoutIntervalInMillis = Integer.parseInt(requestTimeoutInterval);
			setRequestTimeoutIntervalInMillis(requestTimeoutIntervalInMillis);
			logger.info(LOGTAG + "requestTimeoutIntervalInMillis is: " + 
				getRequestTimeoutIntervalInMillis());
		
		logger.info(LOGTAG + "Initialization complete.");
	}
	
	protected List<Property> run() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[VerifyVpnConnectionProvisioning.run] ";
		logger.info(LOGTAG + "Begin running the step.");
		
		boolean vpnConnectionProvisioningSuccess = false;
		boolean vpnConnectionProvisioningPartialSuccess = false;
		String stepResult = FAILURE_RESULT;
		
		// Get the vpnConnectionProvisioningId property from a previous step.
		String provisioningId = getStepPropertyValue("PROVISION_VPN_CONNECTION", 
			"vpnConnectionProvisioningId");
		
		// While the step run time is less that the maxWaitTime,
		// query for the VpnConnectionProvisioning object and 
		// evaluate it for success or failure.
		while (System.currentTimeMillis() - startTime < getMaxWaitTimeInMillis()) {
			
			// Sleep for the sleep interval.
			logger.info(LOGTAG + "Sleeping for " + getSleepTimeInMillis() +
				" prior to next VpnConnectionProvisioning query.");
			try {
				Thread.sleep(getSleepTimeInMillis());
			}
			catch (InterruptedException ie) {
				String errMsg = "Error occurred sleeping.";
				logger.error(LOGTAG + errMsg + ie.getMessage());
				throw new StepException(errMsg, ie);
			}

			// Query for the VpnConnectionProvisioning object.
			VpnConnectionProvisioning vcp = queryForVpnProvisioning(provisioningId);
			
			// If the VpnConnectionProvisioning is successful, log it,
			// and set result properties.
			if (isSuccess(vcp)) {
				vpnConnectionProvisioningSuccess = true;
				stepResult = SUCCESS_RESULT;
				addResultProperty("provisioningMessage", "Both VPN tunnels " +
					"configured properly in the time allowed.");
				break;
			}
		}
		
		// If the max wait time has expired and provisioning is not completely
		// successful, evaluate the results for partial success.
		if (vpnConnectionProvisioningSuccess != true) {
			// Query for the VpnConnectionProvisioning object.
			VpnConnectionProvisioning vcp = queryForVpnProvisioning(provisioningId);
			
			// If the VpnConnectionProvisioning is successful, log it,
			// and set result properties.
			if (isPartialSuccess(vcp)) {
				vpnConnectionProvisioningPartialSuccess = true;
				stepResult = SUCCESS_RESULT;
				addResultProperty("provisioningMessage", "Only one " +
					"site-to-site VPN tunnel configured properly in the time " +
					"allowed. The connection should still operate and be " +
					"completed automatically later.");
			}
		}
		
		// Set return properties.
		addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);
		addResultProperty("maxWaitTimeInMillis", Integer.toString(getMaxWaitTimeInMillis()));
		addResultProperty("sleepTimeInMillis", Integer.toString(getMaxWaitTimeInMillis()));
		addResultProperty("vpnConnectionProvisioningSuccess", 
			Boolean.toString(vpnConnectionProvisioningSuccess));
		addResultProperty("vpnConnectionProvisioningPartialSuccess", 
			Boolean.toString(vpnConnectionProvisioningPartialSuccess));
		
		// Update the step.
    	update(COMPLETED_STATUS, stepResult);
    	
    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step run completed in " + time + "ms.");
    	
    	// Return the properties.
    	return getResultProperties();
    	
	}
	
	protected List<Property> simulate() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[VerifyVpnConnectionProvisioning.simulate] ";
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
			"[VerifyVpnConnectionProvisioning.fail] ";
		logger.info(LOGTAG + "Begin step failure simulation.");
		
		// Set return properties.
		ArrayList<Property> props = new ArrayList<Property>();
    	addResultProperty("stepExecutionMethod", FAILURE_EXEC_TYPE);
		
		// Update the step.
    	update(COMPLETED_STATUS, FAILURE_RESULT);
    	
    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step failure simulation completed in " + time + "ms.");
    	
    	// Return the properties.
    	return props;
	}
	
	public void rollback() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[VerifyVpnConnectionProvisioning.rollback] ";
		logger.info(LOGTAG + "Rollback called, but this step has nothing to " + 
			"roll back.");
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
	
	private void setRequestTimeoutIntervalInMillis(int time) {
		m_requestTimeoutIntervalInMillis = time;
	}
	
	private int getRequestTimeoutIntervalInMillis() {
		return m_requestTimeoutIntervalInMillis;
	}
	
	private boolean isSuccess(VpnConnectionProvisioning vcp) {
		
		if (vcp.getProvisioningResult().equalsIgnoreCase(SUCCESS_RESULT)) {
			return true;
		}
		else return false;
	}
	
	private boolean isPartialSuccess(VpnConnectionProvisioning vcp) {
		
		List<ProvisioningStep> steps = vcp.getProvisioningStep();
		ListIterator<ProvisioningStep> li = steps.listIterator();
		while (li.hasNext()) {
			ProvisioningStep step = (ProvisioningStep)li.next();
			if(step.getType().equalsIgnoreCase("GENERATE_VPN_CONNECTION_ON_ROUTER1") ||
			   step.getType().equalsIgnoreCase("GENERATE_VPN_CONNECTION_ON_ROUTER2")) {
				if (step.getStepResult() != null) {
					if (step.getStepResult().equalsIgnoreCase(SUCCESS_RESULT)) {
						return true;
					}
				}
			}
		}
		
		return false;
	}
	
	
	private VpnConnectionProvisioning queryForVpnProvisioning(String provisioningId)
		throws StepException {
		
		String LOGTAG = getStepTag() + "[VerifyVpnConnection.queryForVpnProvisioning] ";
		
	    // Get a configured VpnConnectionProvisioning object and
	    // VpnConnectionProvisioningQuerySpecification object from AppConfig
	    VpnConnectionProvisioning vpnProvisioning = new 
			VpnConnectionProvisioning();
		VpnConnectionProvisioningQuerySpecification querySpec = 
			new VpnConnectionProvisioningQuerySpecification();
	    try {
	    	vpnProvisioning = (VpnConnectionProvisioning)getAppConfig()
		    		.getObjectByType(vpnProvisioning.getClass().getName());
	    	querySpec = (VpnConnectionProvisioningQuerySpecification)getAppConfig()
		    		.getObjectByType(querySpec.getClass().getName());
	    }
	    catch (EnterpriseConfigurationObjectException ecoe) {
	    	String errMsg = "An error occurred retrieving an object from " +
	    	  "AppConfig. The exception is: " + ecoe.getMessage();
	    	logger.error(LOGTAG + errMsg);
	    	throw new StepException(errMsg, ecoe);
	    }
		
	    // Set the values of the query spce.
	    try {
	    	querySpec.setProvisioningId(provisioningId);
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
			results = vpnProvisioning.generate(querySpec, rs);
			long queryTime = System.currentTimeMillis() - queryStartTime;
			logger.info(LOGTAG + "Queried for VpnConnectionProvisioning" +
				" with ProvisioningId " + provisioningId + "in " + queryTime +
				"ms. There are " + results.size() + " result(s).");
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
			return vcp;
		}
		else {
			String errMsg = "Invalid number of results returned from " +
				"VpnConnectionProvisioning.Query-Request. " +
				results.size() + " results returned. " +
				"Expected exactly 1.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}	
	}
}
