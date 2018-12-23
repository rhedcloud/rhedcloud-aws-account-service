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
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectDeleteException;
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
import edu.emory.moa.objects.resources.v1_0.TunnelProfile;
import edu.emory.moa.objects.resources.v1_0.VpnConnectionProfileAssignmentQuerySpecification;
import edu.emory.moa.objects.resources.v1_0.VpnConnectionProfileAssignmentRequisition;
import edu.emory.moa.objects.resources.v1_0.VpnConnectionProfileQuerySpecification;

/**
 * Example step that can serve as a placholder.
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 21 May 2017
 **/
public class UpdateVpnConnectionAssignment extends AbstractStep implements Step {
	
	private ProducerPool m_networkOpsServiceProducerPool = null;

	public void init (String provisioningId, Properties props, 
			AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp) 
			throws StepException {
		
		super.init(provisioningId, props, aConfig, vpcpp);
		
		String LOGTAG = getStepTag() + "[UpdateVpnConnectionAssignment.init] ";
		
		// This step needs to send messages to the Network Ops Service
		// to update or delete the VpnConnectionProfileAssignment.
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
		
		logger.info(LOGTAG + "Initialization complete.");
	}
	
	protected List<Property> run() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[UpdateVpnConnectionAssignment.run] ";
		logger.info(LOGTAG + "Begin running the step.");
		
		// Get the VpcId property from a previous step.
		String vpcId = 
			getStepPropertyValue("CREATE_VPC_TYPE1_CFN_STACK", "VpcId");
		
		// Get a configured VpnConnectionProfileAssignment and
		// VpnConnectionProfileAssignmentQuerySpecification from
		// AppConfig
		VpnConnectionProfileAssignment vcpa = new 
			VpnConnectionProfileAssignment();
		VpnConnectionProfileAssignmentQuerySpecification querySpec = 
			new VpnConnectionProfileAssignmentQuerySpecification();
	    try {
	    	vcpa = (VpnConnectionProfileAssignment)getAppConfig()
		    		.getObjectByType(vcpa.getClass().getName());
	    	querySpec = (VpnConnectionProfileAssignmentQuerySpecification)getAppConfig()
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
	    	querySpec.setOwnerId(provisioningId);
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
	    
		List assignmentResults = null;
		try { 
			long generateStartTime = System.currentTimeMillis();
			assignmentResults = vcpa.query(querySpec, rs);
			long generateTime = System.currentTimeMillis() - generateStartTime;
			logger.info(LOGTAG + "Queried for VpnConnectionProfileAssignment " +
				"for ProvisioningId " + provisioningId + " in "
				+ generateTime + " ms. Returned " + assignmentResults.size() + 
				" result(s).");
		}
		catch (EnterpriseObjectQueryException eoqe) {
			String errMsg = "An error occurred querying for the  " +
	    	  "VpnConnectionProfileAssignmnet object. " +
	    	  "The exception is: " + eoqe.getMessage();
	    	logger.error(LOGTAG + errMsg);
	    	throw new StepException(errMsg, eoqe);
		}
		finally {
			// Release the producer back to the pool
			getNetworkOpsServiceProducerPool()
				.releaseProducer((MessageProducer)rs);
		}
		
		// If there is exactly one result, update the 
		// VpnConnectionProfileAssignment to reflect the new
		// VpcId
		if (assignmentResults.size() == 1) {
			vcpa = (VpnConnectionProfileAssignment)assignmentResults.get(0);
			
		    // Set the values of the VpnConnectionProfileAssignment.
		    try {
		    	vcpa.setOwnerId(vpcId);
		    }
		    catch (EnterpriseFieldException efe) {
		    	String errMsg = "An error occurred setting the values of the " +
		  	    	  "object. The exception is: " + efe.getMessage();
		  	    logger.error(LOGTAG + errMsg);
		  	    throw new StepException(errMsg, efe);
		    }
		    
		    // Log the state of the object.
		    try {
		    	logger.info(LOGTAG + "Query spec is: " + vcpa.toXmlString());
		    }
		    catch (XmlEnterpriseObjectException xeoe) {
		    	String errMsg = "An error occurred serializing the query " +
		  	    	  "spec to XML. The exception is: " + xeoe.getMessage();
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
		    
			List profileResults = null;
			try { 
				long updateStartTime = System.currentTimeMillis();
				vcpa.update(rs);
				long updateTime = System.currentTimeMillis() - updateStartTime;
				logger.info(LOGTAG + "Updated VpnConnectionProfileAssignment" +
					"for ProvisioningId " + provisioningId + " in "
					+ updateTime + " ms.");
				// Add step properties
				addResultProperty("updatedVpnConnectionProfileAssignment", "true");
				addResultProperty("vpnConnectionProfileAssignmentId", 
						vcpa.getVpnConnectionProfileAssignmentId());
				addResultProperty("vpnConnectionProfileId", 
						vcpa.getVpnConnectionProfileId());
				addResultProperty("vpcId", vpcId);
			}
			catch (EnterpriseObjectUpdateException eoue) {
				String errMsg = "An error occurred updating the  " +
		    	  "VpnConnectionProfileAssignment object. The " +
		    	  "exception is: " + eoue.getMessage();
		    	logger.error(LOGTAG + errMsg);
		    	throw new StepException(errMsg, eoue);
			}
			finally {
				// Release the producer back to the pool
				getNetworkOpsServiceProducerPool()
					.releaseProducer((MessageProducer)rs);
			}
		}
	    // If there is not exactly one assignment returned, log it and 
		// throw an exception.
		else {
			String errMsg = "Invalid number of results returned from " +
				"VpnConnectionProfileAssignment.Query-Request. " +
				assignmentResults.size() + " results returned. " +
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
			"[UpdateVpnConnectionAssignment.simulate] ";
		logger.info(LOGTAG + "Begin step simulation.");
		
		// Set return properties.
    	addResultProperty("stepExecutionMethod", SIMULATED_EXEC_TYPE);
		
		// Update the step.
    	update(COMPLETED_STATUS, SUCCESS_RESULT);
    	
    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step simulation completed in " 
    		+ time + "ms.");
    	
    	// Return the properties.
    	return getResultProperties();
	}
	
	protected List<Property> fail() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[UpdateVpnConnectionAssignment.fail] ";
		logger.info(LOGTAG + "Begin step failure simulation.");
		
		// Set return properties.
    	addResultProperty("stepExecutionMethod", FAILURE_EXEC_TYPE);
		
		// Update the step.
    	update(COMPLETED_STATUS, FAILURE_RESULT);
    	
    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step failure simulation completed in "
    		+ time + "ms.");
    	
    	// Return the properties.
    	return getResultProperties();
	}
	
	public void rollback() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[UpdateVpnConnectionAssignment.rollback] ";
		
		// Get the VpcId property from a previous step.
		String vpcId = 
			getStepPropertyValue("CREATE_VPC_TYPE1_CFN_STACK", "VpcId");
		
		// Get a configured VpnConnectionProfileAssignment and
		// VpnConnectionProfileAssignmentQuerySpecification from
		// AppConfig
		VpnConnectionProfileAssignment vcpa = new 
			VpnConnectionProfileAssignment();
		VpnConnectionProfileAssignmentQuerySpecification querySpec = 
			new VpnConnectionProfileAssignmentQuerySpecification();
	    try {
	    	vcpa = (VpnConnectionProfileAssignment)getAppConfig()
		    		.getObjectByType(vcpa.getClass().getName());
	    	querySpec = (VpnConnectionProfileAssignmentQuerySpecification)getAppConfig()
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
	    	querySpec.setOwnerId(provisioningId);
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
	    
		List assignmentResults = null;
		try { 
			long generateStartTime = System.currentTimeMillis();
			assignmentResults = vcpa.query(querySpec, rs);
			long generateTime = System.currentTimeMillis() - generateStartTime;
			logger.info(LOGTAG + "Queried for VpnConnectionProfileAssignment " +
				"for ProvisioningId " + provisioningId + " in "
				+ generateTime + " ms. Returned " + assignmentResults.size() + 
				" result(s).");
		}
		catch (EnterpriseObjectQueryException eoqe) {
			String errMsg = "An error occurred querying for the  " +
	    	  "VpnConnectionProfileAssignmnet object. " +
	    	  "The exception is: " + eoqe.getMessage();
	    	logger.error(LOGTAG + errMsg);
	    	throw new StepException(errMsg, eoqe);
		}
		finally {
			// Release the producer back to the pool
			getNetworkOpsServiceProducerPool()
				.releaseProducer((MessageProducer)rs);
		}
		
		// If there is exactly one result, delete the 
		// VpnConnectionProfileAssignment to reflect the new
		// VpcId
		if (assignmentResults.size() == 1) {
		    
			vcpa = (VpnConnectionProfileAssignment)assignmentResults.get(0);
		    
		    // Log the state of the object.
		    try {
		    	logger.info(LOGTAG + "VpnConectionProfileAssignment is: " + vcpa.toXmlString());
		    }
		    catch (XmlEnterpriseObjectException xeoe) {
		    	String errMsg = "An error occurred serializing the query " +
		  	    	  "spec to XML. The exception is: " + xeoe.getMessage();
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

			// Delete the assignment
			try { 
				long deleteStartTime = System.currentTimeMillis();
				vcpa.delete("Delete", rs);
				long deleteTime = System.currentTimeMillis() - deleteStartTime;
				logger.info(LOGTAG + "Updated VpnConnectionProfileAssignment" +
					"for ProvisioningId " + provisioningId + " in "
					+ deleteTime + " ms.");
				// Add step properties
				addResultProperty("deletedVpnConnectionProfileAssignment", "true");
			}
			catch (EnterpriseObjectDeleteException eode) {
				String errMsg = "An error occurred deleting the  " +
		    	  "VpnConnectionProfileAssignment object. The " +
		    	  "exception is: " + eode.getMessage();
		    	logger.error(LOGTAG + errMsg);
		    	throw new StepException(errMsg, eode);
			}
			finally {
				// Release the producer back to the pool
				getNetworkOpsServiceProducerPool()
					.releaseProducer((MessageProducer)rs);
			}
		}
	    // If there is not exactly one assignment returned, log it and 
		// throw an exception.
		else {
			String errMsg = "Invalid number of results returned from " +
				"VpnConnectionProfileAssignment.Query-Request. " +
				assignmentResults.size() + " results returned. " +
				"Expected exactly 1.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		
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
	
}
