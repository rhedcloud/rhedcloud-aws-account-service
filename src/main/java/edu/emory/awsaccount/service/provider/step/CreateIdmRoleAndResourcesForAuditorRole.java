/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2018 Emory University. All rights reserved. 
 ******************************************************************************/
package edu.emory.awsaccount.service.provider.step;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
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

import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;
import edu.emory.moa.jmsobjects.identity.v1_0.Entitlement;
import edu.emory.moa.jmsobjects.identity.v1_0.Resource;
import edu.emory.moa.jmsobjects.identity.v1_0.Role;
import edu.emory.moa.objects.resources.v1_0.RoleRequisition;

/**
 * If this is a new account request, create an IDM role for the
 * account auditor role.
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 27 December 2018
 **/
public class CreateIdmRoleAndResourcesForAuditorRole extends AbstractStep implements Step {
	
	private ProducerPool m_idmServiceProducerPool = null;
	private int m_requestTimeoutIntervalInMillis = 10000;
	private String m_resource3EntitlementDn = null;

	public void init (String provisioningId, Properties props, 
			AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp) 
			throws StepException {
		
		super.init(provisioningId, props, aConfig, vpcpp);
		
		String LOGTAG = getStepTag() + "[CreateIdmRoleAndResourcesForAuditorRole.init] ";
		
		// This step needs to send messages to the IDM service
		// to create account roles.
		ProducerPool p2p1 = null;
		try {
			p2p1 = (ProducerPool)getAppConfig()
				.getObject("IdmServiceProducerPool");
			setIdmServiceProducerPool(p2p1);
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			// An error occurred retrieving an object from AppConfig. Log it and
			// throw an exception.
			String errMsg = "An error occurred retrieving an object from " +
					"AppConfig. The exception is: " + ecoe.getMessage();
			logger.fatal(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
		
		// Get custom step properties.
		logger.info(LOGTAG + "Getting custom step properties...");
			
		String requestTimeoutInterval = getProperties()
				.getProperty("requestTimeoutIntervalInMillis", "10000");
			int requestTimeoutIntervalInMillis = Integer.parseInt(requestTimeoutInterval);
			setRequestTimeoutIntervalInMillis(requestTimeoutIntervalInMillis);
			logger.info(LOGTAG + "requestTimeoutIntervalInMillis is: " + 
				getRequestTimeoutIntervalInMillis());
			
		String resource3EntitlementDn = getProperties()
				.getProperty("resource3EntitlementDn");
		setResource4EntitlementDn(resource3EntitlementDn);
		logger.info(LOGTAG + "resource3EntitlementDn is: " +
			resource3EntitlementDn);
		logger.info(LOGTAG + getResource3EntitlementDn());

		logger.info(LOGTAG + "Initialization complete.");
		
	}
	
	protected List<Property> run() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[CreateIdmRoleAndResourcesForAuditorRole.run] ";
		logger.info(LOGTAG + "Begin running the step.");
		
		boolean generatedRole = false;
		
		// Return properties
		addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);
		
		// Get some properties from previous steps.
		String allocateNewAccount = 
			getStepPropertyValue("GENERATE_NEW_ACCOUNT", "allocateNewAccount");
		String newAccountId = 
			getStepPropertyValue("GENERATE_NEW_ACCOUNT", "newAccountId");
		
		boolean allocatedNewAccount = Boolean.parseBoolean(allocateNewAccount) ;
		logger.info(LOGTAG + "allocatedNewAccount: " + allocatedNewAccount);
		logger.info(LOGTAG + "newAccountId: " + newAccountId);
		
		// If allocatedNewAccount is true and newAccountId is not null, 
		// Send a Role.Generate-Request to the AWS Account service.
		if (allocatedNewAccount && (newAccountId != null && newAccountId.equalsIgnoreCase("null") == false)) {
			logger.info(LOGTAG + "allocatedNewAccount is true and newAccountId " + 
				"is not null. Sending a Role.Generate-Request to generate an IDM" +
				"role.");
			
			String auditorRoleGuid = 
				getStepPropertyValue("CREATE_LDS_GROUP_FOR_AUDITOR_ROLE", "guid");
			
			// Get a configured Role object and RoleRequisision from AppConfig.
			Role role = new Role();
			RoleRequisition req = new RoleRequisition();
		    try {
		    	role = (Role)getAppConfig()
			    	.getObjectByType(role.getClass().getName());
		    	req = (RoleRequisition)getAppConfig()
		    		.getObjectByType(req.getClass().getName());
		    }
		    catch (EnterpriseConfigurationObjectException ecoe) {
		    	String errMsg = "An error occurred retrieving an object from " +
		    	  "AppConfig. The exception is: " + ecoe.getMessage();
		    	logger.error(LOGTAG + errMsg);
		    	throw new StepException(errMsg, ecoe);
		    }
		    
		    // Set the values of the requisition.
		    try {
		    	// Main fields
		    	String roleNameTemplate = "RGR_AWS-ACCOUNT_NUMBER-RHEDcloudAuditorRole";
		    	req.setRoleName(roleNameTemplate.replace("ACCOUNT_NUMBER", newAccountId));
		    	req.setRoleDescription("Provisions members to various AWS resources");
		    	req.setRoleCategoryKey("aws");
		    	
		    	// Resource 1
		    	Resource res1 = role.newResource();
		    	String res1name = "MDSG_AWS-ACCOUNT_NUMBER-RHEDcloudAuditorRole";
		    	res1.setResourceName(res1name.replace("ACCOUNT_NUMBER", newAccountId));
		    	res1.setResourceDescription("Provisions members to group RHEDcloudAdministratorRole on MS LDS University Connector");
		    	res1.setResourceCategoryKey("group");
		    	Entitlement ent1 = res1.newEntitlement();
		    	String ent1dn = "CN=RHEDcloudAuditorRole,OU=ACCOUNT_NUMBER,OU=AWS,DC=emory,DC=edu";
		    	ent1.setEntitlementDN(ent1dn.replace("ACCOUNT_NUMBER", newAccountId));
		    	ent1.setEntitlementGuid(auditorRoleGuid);
		    	ent1.setEntitlementApplication("UMD");
		    	res1.setEntitlement(ent1);
		    	req.addResource(res1);
		    	
		    	// Resource 2
		    	Resource res2 = role.newResource();
		    	String res2name = "HDSG_AWS-ACCOUNT_NUMBER-RHEDcloudAuditorRole";
		    	res2.setResourceName(res2name.replace("ACCOUNT_NUMBER", newAccountId));
		    	res2.setResourceDescription("Provisions members to group RHEDcloudAuditorRole on MS LDS Healthcare Connector");
		    	res2.setResourceCategoryKey("group");
		    	Entitlement ent2 = res2.newEntitlement();
		    	String ent2dn = "CN=RHEDcloudAuditorRole,OU=ACCOUNT_NUMBER,OU=AWS,DC=emory,DC=edu";
		    	ent2.setEntitlementDN(ent2dn.replace("ACCOUNT_NUMBER", newAccountId));
		    	ent2.setEntitlementGuid(auditorRoleGuid);
		    	ent2.setEntitlementApplication("HMD");
		    	res2.setEntitlement(ent2);
		    	req.addResource(res2);
		    	
		    	// Resource 3
		    	Resource res3 = role.newResource();
		    	res3.setResourceName("RGR_AwsUsers");
		    	res3.setResourceDescription("Provisions members to group AwsUsers on IDV Roles LBD Connector. This group contains all AWS users.");
		    	res3.setResourceCategoryKey("group");
		    	Entitlement ent3 = res3.newEntitlement();
		    	ent3.setEntitlementDN("\\EMORYDEV\\EmoryDev\\Data\\Groups\\AwsUsers");
		    	ent3.setEntitlementApplication("IDV");
		    	res3.setEntitlement(ent3);
		    	req.addResource(res3);
		    }
		    catch (EnterpriseFieldException efe) {
		    	String errMsg = "An error occurred setting the values of the " +
		  	    	  "AccountAlias. The exception is: " + efe.getMessage();
		  	    logger.error(LOGTAG + errMsg);
		  	    throw new StepException(errMsg, efe);
		    }
		    
		    // Log the state of the RoleRequisition.
		    try {
		    	logger.info(LOGTAG + "Role req is: " +
		    		req.toXmlString());
		    }
		    catch (XmlEnterpriseObjectException xeoe) {
		    	String errMsg = "An error occurred serializing the object " +
		  	    	  "to XML. The exception is: " + xeoe.getMessage();
	  	    	logger.error(LOGTAG + errMsg);
	  	    	throw new StepException(errMsg, xeoe);
		    }    
			
			// Get a producer from the pool
			RequestService rs = null;
			try {
				PointToPointProducer p2p = 
					(PointToPointProducer)getIdmServiceProducerPool()
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
				long generateStartTime = System.currentTimeMillis();
				results = role.generate(req, rs);
				long generateTime = System.currentTimeMillis() - generateStartTime;
				logger.info(LOGTAG + "Generated Role in " + generateTime +
					" ms.");
				generatedRole = true;
				addResultProperty("allocatedNewAccount", 
					Boolean.toString(allocatedNewAccount));
				addResultProperty("generatedRole", 
					Boolean.toString(generatedRole));
			}
			catch (EnterpriseObjectGenerateException eoge) {
				String errMsg = "An error occurred generating the object. " +
		    	  "The exception is: " + eoge.getMessage();
		    	logger.error(LOGTAG + errMsg);
		    	throw new StepException(errMsg, eoge);
			}
			finally {
				// Release the producer back to the pool
				getIdmServiceProducerPool()
					.releaseProducer((MessageProducer)rs);
			}
			
			// If there is exactly one result, log it.
			if (results.size() == 1) {
				role = (Role)results.get(0);
				try {
					logger.info(LOGTAG + "Generated role: " + role.toXmlString());
				}
				catch (XmlEnterpriseObjectException xeoe) {
			    	String errMsg = "An error occurred serializing the object " +
			  	    	  "to XML. The exception is: " + xeoe.getMessage();
		  	    	logger.error(LOGTAG + errMsg);
		  	    	throw new StepException(errMsg, xeoe);
			    }   
			}
			
		}
		// If allocatedNewAccount is false, log it and add result props.
		else {
			logger.info(LOGTAG + "allocatedNewAccount is false. " +
				"no need to generate a new role.");
			addResultProperty("allocatedNewAccount", 
				Boolean.toString(allocatedNewAccount));
			addResultProperty("generatedRole", 
				"not applicable");
		}
		
		// Update the step result.
		String stepResult = FAILURE_RESULT;
		if (generatedRole == true && allocatedNewAccount == true) {
			stepResult = SUCCESS_RESULT;
		}
		if (allocatedNewAccount == false) {
			stepResult = SUCCESS_RESULT;
		}
		
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
			"[CreateIdmRoleAndResourcesForAuditorRole.simulate] ";
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
			"[CreateIdmRoleAndResourcesForAuditorRole.fail] ";
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
		super.rollback();
		String LOGTAG = getStepTag() + 
				"[CreateIdmRoleAndResourcesForAuditorRole.rollback] ";
		long startTime = System.currentTimeMillis();
		
		logger.info(LOGTAG + "Rollback called, but this step has nothing to " + 
			"roll back.");
		update(ROLLBACK_STATUS, SUCCESS_RESULT);
		
		// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
	}
	
	private void setIdmServiceProducerPool(ProducerPool pool) {
		m_idmServiceProducerPool = pool;
	}
	
	private ProducerPool getIdmServiceProducerPool() {
		return m_idmServiceProducerPool;
	}
	
	private void setRequestTimeoutIntervalInMillis(int time) {
		m_requestTimeoutIntervalInMillis = time;
	}
	
	private int getRequestTimeoutIntervalInMillis() {
		return m_requestTimeoutIntervalInMillis;
	}
	private void setResource3EntitlementDn (String dn) throws 
		StepException {
	
		if (dn == null) {
			String errMsg = "resource3EntitlementDn property is null. " +
				"Can't continue.";
			throw new StepException(errMsg);
		}
	
		m_resource3EntitlementDn = dn;
	}
	
	private String getResource3EntitlementDn() {
		return m_resource3EntitlementDn;
	}
}
