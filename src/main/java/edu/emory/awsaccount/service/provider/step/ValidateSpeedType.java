/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2017 Emory University. All rights reserved. 
 ******************************************************************************/
package edu.emory.awsaccount.service.provider.step;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.VirtualPrivateCloudProvisioning;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.ProvisioningStep;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudRequisition;
import com.oracle.peoplesoft.moa.jmsobjects.finance.v1_0.SPEEDCHART;
import com.oracle.peoplesoft.moa.objects.resources.v1_0.SPEEDCHART_QUERY;
import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.jms.producer.PointToPointProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.transport.RequestService;

import javax.jms.JMSException;
import java.util.List;
import java.util.Properties;

/**
 * If a this is a request for a new VPC in an existing account,
 * send RoleAssignment.Query-Request to determine if the user
 * is an account administrator or central administrator of the
 * account.
 * <P>
 * 
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 5 August 2018
 **/
public class ValidateSpeedType extends AbstractStep implements Step {
	
	private String m_stackName = null;
	private long m_maxWaitTimeInMillis = 60000;
	private ProducerPool peopleSoftProducerPool = null;


	public void init (String provisioningId, Properties props, AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp) throws StepException {
		
		super.init(provisioningId, props, aConfig, vpcpp);


		String LOGTAG = getStepTag() + "[ValidateSpeedType.init] ";


		ProducerPool p2p1 = null;

		try {
			p2p1 = (ProducerPool)getAppConfig().getObject("PeopleSoftServiceProducerPool");
			setPeopleSoftServiceProducerPool(p2p1);
		}
		catch (EnterpriseConfigurationObjectException ecoe) {
			// An error occurred retrieving an object from AppConfig. Log it and
			// throw an exception.
			String errMsg = "An error occurred retrieving an object from " + "AppConfig. The exception is: " + ecoe.getMessage();
			logger.fatal(LOGTAG + errMsg);
			throw new StepException(errMsg);
		}
			
		logger.info(LOGTAG + "Getting custom step properties...");
		String stackName = getProperties().getProperty("stackName", null);
		setStackName(stackName);
		logger.info(LOGTAG + "stackName is: " + getStackName());
		
		String sMaxWaitTime = getProperties().getProperty("maxWaitTimeInMillis", "60000");
		setMaxWaitTimeInMillis(Long.parseLong(sMaxWaitTime));
		
		logger.info(LOGTAG + "Initialization complete.");
		
	}
	
	protected List<Property> run() throws StepException {

		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[ValidateSpeedType.run] ";
		logger.info(LOGTAG + "Begin running the step.");

		addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);
		

		ProvisioningStep step = getProvisioningStepByType("DETERMINE_NEW_OR_EXISTING_ACCOUNT");

		VirtualPrivateCloudProvisioning virtualPrivateCloudProvisioning = getVirtualPrivateCloudProvisioning();
		VirtualPrivateCloudRequisition virtualPrivateCloudRequisition = virtualPrivateCloudProvisioning.getVirtualPrivateCloudRequisition();

		SPEEDCHART speedchart = new SPEEDCHART();
		SPEEDCHART_QUERY querySpec = new SPEEDCHART_QUERY();

		try {
			speedchart = (SPEEDCHART)getAppConfig().getObjectByType(speedchart.getClass().getName());
			querySpec = (SPEEDCHART_QUERY)getAppConfig().getObjectByType(querySpec.getClass().getName());
		} catch (EnterpriseConfigurationObjectException ecoe) {
			String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + ecoe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg, ecoe);
		}

		querySpec.addSPEEDCHART_KEY(virtualPrivateCloudRequisition.getFinancialAccountNumber());

		// Get a RequestService to use for this transaction.
		RequestService rs = null;

		try {
			rs = (RequestService)getPeopleSoftServiceProducerPool().getExclusiveProducer();
		}
		catch (JMSException jmse) {
			String errMsg = "An error occurred getting a request service to use in this transaction. The exception is: " + jmse.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg, jmse);
		}

		// Query for the SPEEDCHART.
		List<SPEEDCHART> speedchartList = null;
		String stepResult = null;

		try {
			startTime = System.currentTimeMillis();
			speedchartList = speedchart.query(querySpec, rs);
			long time = System.currentTimeMillis() - startTime;

			if (speedchartList != null) {
				if (speedchartList.size() == 1) {

					SPEEDCHART speedchart1 = speedchartList.get(0);

					if (speedchart1.getVALID_CODE().equals("Y")) {
						stepResult = SUCCESS_RESULT;
						addResultProperty("validateSpeedType", speedchart1.getEU_VALIDITY_DESCR());
					} else if (speedchart1.getVALID_CODE().equals("N")) {
						// invalid
						stepResult = FAILURE_RESULT;
						addResultProperty("validateSpeedType", speedchart1.getEU_VALIDITY_DESCR());
					} else {
						stepResult = FAILURE_RESULT; // it will tell me if it's a warning
						addResultProperty("validateSpeedType", speedchart1.getEU_VALIDITY_DESCR());
					}

				} else {
					// Error
					stepResult = FAILURE_RESULT;
					addResultProperty("validateSpeedType", speedchart.getEU_VALIDITY_DESCR());
				}
			}



			update(COMPLETED_STATUS, stepResult);


			logger.info(LOGTAG + "Queried for stack for " +
					"accountId " + virtualPrivateCloudRequisition.getFinancialAccountNumber() + " and stackName " + speedchartList +
					"in " + time + " ms. Returned " +
					speedchartList.size() + " stack(s).");


		} catch (EnterpriseObjectQueryException eoqe) {
			String errMsg = "An error occurred querying for the Stack objects The exception is: " + eoqe.getMessage();
			logger.error(LOGTAG + errMsg);
			throw new StepException(errMsg, eoqe);
		} finally {
			getPeopleSoftServiceProducerPool().releaseProducer((PointToPointProducer)rs);
		}



		return getResultProperties();
	}
	
	protected List<Property> simulate() throws StepException {
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[AuthorizeExistingAccountRequestor.simulate] ";
		logger.info(LOGTAG + "Begin step simulation.");
		
		// Set return properties.
    	addResultProperty("stepExecutionMethod", SIMULATED_EXEC_TYPE);
    	addResultProperty("isAuthorized", "true");
		
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
		String LOGTAG = getStepTag() + "[AuthorizeExistingAccountRequestor.fail] ";
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
		
		long startTime = System.currentTimeMillis();
		String LOGTAG = getStepTag() + "[AuthorizeExistingAccountRequestor.rollback] ";
		logger.info(LOGTAG + "Rollback called, but this step has nothing to roll back.");
		update(ROLLBACK_STATUS, SUCCESS_RESULT);
		
		// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
	}

	private ProducerPool getPeopleSoftServiceProducerPool() {
		return peopleSoftProducerPool;
	}
	
	private void setPeopleSoftServiceProducerPool(ProducerPool pool) {
		peopleSoftProducerPool = pool;
	}
	
	private String getStackName() {
		return m_stackName;
	}
	
	private void setStackName(String stackName) {
		m_stackName = stackName;
	}
	
	private Long getMaxWaitTimeInMillis() {
		return m_maxWaitTimeInMillis;
	}
	
	private void setMaxWaitTimeInMillis(long maxWaitTimeInMillis) {
		m_maxWaitTimeInMillis = maxWaitTimeInMillis;
	}
	
	private long getQueryTimeInMillis(long queryStartTime) {
		return System.currentTimeMillis() - queryStartTime;
	}

}
