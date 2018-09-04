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
import org.openeai.moa.EnterpriseObjectDeleteException;
import org.openeai.moa.EnterpriseObjectGenerateException;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.transport.RequestService;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;
import edu.emory.moa.jmsobjects.network.v1_0.VpnConnectionProfile;
import edu.emory.moa.jmsobjects.network.v1_0.VpnConnectionProfileAssignment;
import edu.emory.moa.objects.resources.v1_0.VpnConnectionProfileAssignmentQuerySpecification;
import edu.emory.moa.objects.resources.v1_0.VpnConnectionProfileAssignmentRequisition;
import edu.emory.moa.objects.resources.v1_0.VpnConnectionProfileQuerySpecification;

/**
 * Send a VpnConnectionProfileAssignment.Generate-Request to the 
 * NetworkOpsService to reserve a VpnConnectionProfile for this
 * provisioning run.
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 2 September 2018
 **/
public class DetermineVpcCidr extends AbstractStep implements Step {
	
	private ProducerPool m_networkOpsServiceProducerPool = null;

	public void init (String provisioningId, Properties props, 
			AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp) 
			throws StepException {
		
		super.init(provisioningId, props, aConfig, vpcpp);
		
		String LOGTAG = getStepTag() + "[DetermineVpcCidr.init] ";
		
		// This step needs to send messages to the Network Ops Service
		// to determine the VPC CIDR.
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
			logger.fatal(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		
		logger.info(LOGTAG + "Initialization complete.");
		
	}
	
	protected List<Property> run() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[DetermineVpcCidr.run] ";
		logger.info(LOGTAG + "Begin running the step.");
		
		boolean isAuthorized = false;
		
		// Return properties
		List<Property> props = new ArrayList<Property>();
		props.add(buildProperty("stepExecutionMethod", RUN_EXEC_TYPE));
		
		// Get the VPC ProvisioningId to use to reserve the VpnConnection Profile
		String provisioningId = getVirtualPrivateCloudProvisioning().getProvisioningId();
		logger.info(LOGTAG + "The ProvisioningId is: " + provisioningId);
		props.add(buildProperty("provisioningId", provisioningId));
		
		// Get a configured VpnConnectionProfile and
		// VpnConnectionProfileRequistion from AppConfig
		VpnConnectionProfileAssignment vcpa = new 
			VpnConnectionProfileAssignment();
		VpnConnectionProfileAssignmentRequisition vcpar = new
			VpnConnectionProfileAssignmentRequisition();
	    try {
	    	vcpa = (VpnConnectionProfileAssignment)getAppConfig()
		    		.getObjectByType(vcpa.getClass().getName());
	    	vcpar = (VpnConnectionProfileAssignmentRequisition)getAppConfig()
		    		.getObjectByType(vcpar.getClass().getName());
	    }
	    catch (EnterpriseConfigurationObjectException ecoe) {
	    	String errMsg = "An error occurred retrieving an object from " +
	    	  "AppConfig. The exception is: " + ecoe.getMessage();
	    	logger.error(LOGTAG + errMsg);
	    	throw new StepException(errMsg, ecoe);
	    }
	    
	    // Set the values of the requisition.
	    try {
	    	vcpar.setOwnerId(provisioningId);
	    }
	    catch (EnterpriseFieldException efe) {
	    	String errMsg = "An error occurred setting the values of the " +
	  	    	  "requisition. The exception is: " + efe.getMessage();
	  	    logger.error(LOGTAG + errMsg);
	  	    throw new StepException(errMsg, efe);
	    }
	    
	    // Log the state of the requisition.
	    try {
	    	logger.info(LOGTAG + "Requistion is: " + vcpar.toXmlString());
	    }
	    catch (XmlEnterpriseObjectException xeoe) {
	    	String errMsg = "An error occurred serializing the requisition " +
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
			assignmentResults = vcpa.generate(vcpar, rs);
			long generateTime = System.currentTimeMillis() - generateStartTime;
			logger.info(LOGTAG + "Generated VpnConnectionProfileAssignment " +
				"for ProvisioningId " + provisioningId + " in "
				+ generateTime + " ms. Returned " + assignmentResults.size() + 
				" result(s).");
		}
		catch (EnterpriseObjectGenerateException eoqe) {
			String errMsg = "An error occurred generating the  " +
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
		
		// If there is exactly one result, query for the VpnConnectionProfile
		if (assignmentResults.size() == 1) {
			VpnConnectionProfileAssignment assignment = 
				(VpnConnectionProfileAssignment)assignmentResults.get(0);
			
			// Get a configured VpnConnectionProfile and a
			// VpnConnectionProfileQuerySpecification from AppConfig
			VpnConnectionProfile vcp = new VpnConnectionProfile();
			VpnConnectionProfileQuerySpecification vcpqs = new
				VpnConnectionProfileQuerySpecification();
		    try {
		    	vcp = (VpnConnectionProfile)getAppConfig()
			    		.getObjectByType(vcp.getClass().getName());
		    	vcpqs = (VpnConnectionProfileQuerySpecification)getAppConfig()
			    		.getObjectByType(vcpar.getClass().getName());
		    }
		    catch (EnterpriseConfigurationObjectException ecoe) {
		    	String errMsg = "An error occurred retrieving an object from " +
		    	  "AppConfig. The exception is: " + ecoe.getMessage();
		    	logger.error(LOGTAG + errMsg);
		    	throw new StepException(errMsg, ecoe);
		    }
		    
		    // Set the values of the query spec.
		    try {
		    	vcpqs.setVpnConnectionProfileId(assignment
		    		.getVpnConnectionProfileId());
		    }
		    catch (EnterpriseFieldException efe) {
		    	String errMsg = "An error occurred setting the values of the " +
		  	    	  "query spec. The exception is: " + efe.getMessage();
		  	    logger.error(LOGTAG + errMsg);
		  	    throw new StepException(errMsg, efe);
		    }
		    
		    // Log the state of the query spec.
		    try {
		    	logger.info(LOGTAG + "Query spec is: " + vcpqs.toXmlString());
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
				long queryStartTime = System.currentTimeMillis();
				profileResults = vcp.query(vcpqs, rs);
				long queryTime = System.currentTimeMillis() - queryStartTime;
				logger.info(LOGTAG + "Queried for VpnConnectionProfile" +
					"for ProvisioningId " + provisioningId + " in "
					+ queryTime + " ms. Returned " + profileResults.size() + 
					" result(s).");
			}
			catch (EnterpriseObjectQueryException eoqe) {
				String errMsg = "An error occurred querying for the  " +
		    	  "VpnConnectionProfile object. The exception is: " +
		    	  eoqe.getMessage();
		    	logger.error(LOGTAG + errMsg);
		    	throw new StepException(errMsg, eoqe);
			}
			finally {
				// Release the producer back to the pool
				getNetworkOpsServiceProducerPool()
					.releaseProducer((MessageProducer)rs);
			}
			
			// If exactly one VpnConnectionProfile is returned, add the 
			// profile id and CIDR to the provisioning properties. Otherwise.
			// log it and throw and exception.
			if (profileResults.size() == 1) {
				VpnConnectionProfile p = (VpnConnectionProfile)profileResults.get(0);
				String profileId = p.getVpnConnectionProfileId();
				String network = p.getVpcNetwork();
				logger.info(LOGTAG + "vpnConnectionProfileId is: " + profileId);
				props.add(buildProperty("vpnConnectionProfileId", profileId));
				logger.info(LOGTAG + "vpcNetwork is: " + network);
				props.add(buildProperty("vpcNetwork", network));
			}
			else {
				String errMsg = "Invalid number of results returned from " +
					"VpnConnectionProfile.Query-Request. " + profileResults.size() +
					" results returned. Expected exactly 1.";
				logger.error(LOGTAG + errMsg);
				throw new StepException(errMsg);
			}
		}
	    // If there is not exactly one assignment returned, log it and 
		// throw an exception.
		else {
			String errMsg = "Invalid number of results returned from " +
				"VpnConnectionProfileAssignment.Generate-Request. " +
				assignmentResults.size() + " results returned. " +
				"Expected exactly 1.";
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
			
		// Update the step.
		update(COMPLETED_STATUS, SUCCESS_RESULT, props);
    	
    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step run completed in " + time + "ms.");
    	
    	// Return the properties.
    	return props;
    	
	}
	
	protected List<Property> simulate() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[DetermineVpcCidr.simulate] ";
		logger.info(LOGTAG + "Begin step simulation.");
		
		// Set return properties.
		ArrayList<Property> props = new ArrayList<Property>();
    	props.add(buildProperty("stepExecutionMethod", SIMULATED_EXEC_TYPE));
    	Property prop = buildProperty("accountSequenceNumber", "10000");
		
		// Update the step.
    	update(COMPLETED_STATUS, SUCCESS_RESULT, props);
    	
    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step simulation completed in " + time + "ms.");
    	
    	// Return the properties.
    	return props;
	}
	
	protected List<Property> fail() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[DetermineVpcCidr.fail] ";
		logger.info(LOGTAG + "Begin step failure simulation.");
		
		// Set return properties.
		ArrayList<Property> props = new ArrayList<Property>();
    	props.add(buildProperty("stepExecutionMethod", FAILURE_EXEC_TYPE));
		
		// Update the step.
    	update(COMPLETED_STATUS, FAILURE_RESULT, props);
    	
    	// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Step failure simulation completed in " + time + "ms.");
    	
    	// Return the properties.
    	return props;
	}
	
	public void rollback() throws StepException {
		
		super.rollback();
		
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + 
			"[DetermineVpcCidr.rollback] ";
		logger.info(LOGTAG + "Rollback called, if vpcConnectionProfileId was " + 
			"set, query for and delete the VpnConnectionProfileAssignment.");

		String vpnConnectionProfileId = 
			getResultProperty("vpnConnectionProfileId");
		
		// If the vpcConnectionProfileId is not null, query for the
		// VpnConnectionProfileAssignment
		if (vpnConnectionProfileId != null) {
			// Get a configured VpnConnectionProfile and
			// VpnConnectionProfileQuerySpecification from AppConfig
			VpnConnectionProfileAssignment vcpa = new 
				VpnConnectionProfileAssignment();
			VpnConnectionProfileAssignmentQuerySpecification vcpqs = new
				VpnConnectionProfileAssignmentQuerySpecification();
		    try {
		    	vcpa = (VpnConnectionProfileAssignment)getAppConfig()
			    		.getObjectByType(vcpa.getClass().getName());
		    	vcpqs = (VpnConnectionProfileAssignmentQuerySpecification)getAppConfig()
			    		.getObjectByType(vcpqs.getClass().getName());
		    }
		    catch (EnterpriseConfigurationObjectException ecoe) {
		    	String errMsg = "An error occurred retrieving an object from " +
		    	  "AppConfig. The exception is: " + ecoe.getMessage();
		    	logger.error(LOGTAG + errMsg);
		    	throw new StepException(errMsg, ecoe);
		    }
		    
		    // Set the values of the query spec.
		    try {
		    	vcpqs.setVpnConnectionProfileId(vpnConnectionProfileId);
		    }
		    catch (EnterpriseFieldException efe) {
		    	String errMsg = "An error occurred setting the values of the " +
		  	    	  "query spec. The exception is: " + efe.getMessage();
		  	    logger.error(LOGTAG + errMsg);
		  	    throw new StepException(errMsg, efe);
		    }
		    
		    // Log the state of the query spec.
		    try {
		    	logger.info(LOGTAG + "Query spec is: " + vcpqs.toXmlString());
		    }
		    catch (XmlEnterpriseObjectException xeoe) {
		    	String errMsg = "An error occurred serializing the query spec " +
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
				long queryStartTime = System.currentTimeMillis();
				assignmentResults = vcpa.query(vcpqs, rs);
				long queryTime = System.currentTimeMillis() - queryStartTime;
				logger.info(LOGTAG + "Queried for VpnConnectionProfileAssignment " +
					"for VpnConnectionProfileId " + vpnConnectionProfileId + " in "
					+ queryTime + " ms. Returned " + assignmentResults.size() + 
					" result(s).");
			}
			catch (EnterpriseObjectQueryException eoqe) {
				String errMsg = "An error occurred querying the  " +
		    	  "VpnConnectionProfileAssignment object. " +
		    	  "The exception is: " + eoqe.getMessage();
		    	logger.error(LOGTAG + errMsg);
		    	throw new StepException(errMsg, eoqe);
			}
			finally {
				// Release the producer back to the pool
				getNetworkOpsServiceProducerPool()
					.releaseProducer((MessageProducer)rs);
			}
			
			// If there is more than one result, this is an
			// error, log it and throw an exception.
			if (assignmentResults.size() > 1) {
				String errMsg = "An unexpected number of VpnConnectionProfile" +
					"Assignments were found. " + assignmentResults.size() +
					" results were found. Only 0 or 1 were expected.";
				logger.error(LOGTAG + errMsg);
				throw new StepException(errMsg);
			}
			
			// If there are no results, there is nothing to delete
			// log it.
			if (assignmentResults.size() == 0) {
				logger.info(LOGTAG + "No VpnConnectionProfileAssignments " +
					"found. Nothing to delete.");
			}
			
			// If there is exactly one assignment, delete it.
			VpnConnectionProfileAssignment resultAssignment = 
				(VpnConnectionProfileAssignment)assignmentResults.get(0);
			String provisioningId = getVirtualPrivateCloudProvisioning()
				.getProvisioningId();
			if (assignmentResults.size() == 1) {
				logger.info(LOGTAG + "There is exactly one VpnConnectionProfile" +
					"Assignment for this VpnConnectionProfileId.");
				if (resultAssignment.getOwnerId().equalsIgnoreCase(provisioningId)) {
					logger.info(LOGTAG + "The VpnConnectionProfile is assigned to the " +
						"ProvisioningId of this run (" + provisioningId + "). Deleting " +
						"the assignment.");
					
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
				    
					try { 
						long deleteStartTime = System.currentTimeMillis();
						resultAssignment.delete("Delete", rs);
						long deleteTime = System.currentTimeMillis() - deleteStartTime;
						logger.info(LOGTAG + "Deleted for VpnConnectionProfileAssignment " +
							"for VpnConnectionProfileId " + vpnConnectionProfileId + " in "
							+ deleteTime + " ms.");
					}
					catch (EnterpriseObjectDeleteException eode) {
						String errMsg = "An error occurred deleting the  " +
				    	  "VpnConnectionProfileAssignment object. " +
				    	  "The exception is: " + eode.getMessage();
				    	logger.error(LOGTAG + errMsg);
				    	throw new StepException(errMsg, eode);
					}
					finally {
						// Release the producer back to the pool
						getNetworkOpsServiceProducerPool()
							.releaseProducer((MessageProducer)rs);
					}
				}
				else {
					logger.info(LOGTAG + "The VpnConnectionProfile is not assigned to the " +
						"ProvisioningId of this run  (" + provisioningId + "). It is assigned " +
						"to the OwnerId: " + resultAssignment.getOwnerId() + 
						". There is no delete required to roll back this step.");
				}
			}
		}
		
		update(ROLLBACK_STATUS, SUCCESS_RESULT, getResultProperties());
		
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