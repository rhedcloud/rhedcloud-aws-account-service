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
import org.openeai.moa.EnterpriseObjectCreateException;
import org.openeai.moa.EnterpriseObjectGenerateException;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.EnterpriseObjectUpdateException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.transport.RequestService;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.Account;
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.VirtualPrivateCloudProvisioning;
import com.amazon.aws.moa.objects.resources.v1_0.AccountQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.Datetime;
import com.amazon.aws.moa.objects.resources.v1_0.EmailAddress;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.ProvisioningStep;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudRequisition;

import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;
import edu.emory.moa.jmsobjects.network.v1_0.VpnConnectionProfile;
import edu.emory.moa.jmsobjects.network.v1_0.VpnConnectionProfileAssignment;
import edu.emory.moa.objects.resources.v1_0.VpnConnectionProfileAssignmentRequisition;
import edu.emory.moa.objects.resources.v1_0.VpnConnectionProfileQuerySpecification;

/**
 * Set the srdExempt account property to prevent SRDs from running against this account.
 * <P>
 *
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 4 February 2019
 **/
public class SetSrdExemptAccountProperty extends AbstractStep implements Step {

	private ProducerPool m_awsAccountServiceProducerPool = null;

	public void init (String provisioningId, Properties props,
			AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp)
			throws StepException {

		super.init(provisioningId, props, aConfig, vpcpp);

		String LOGTAG = getStepTag() + "[SetSrdExemptAccountProperty.init] ";

		// Get custom step properties.
		logger.info(LOGTAG + "Getting custom step properties...");

		// This step needs to send messages to the AWS account service
		// to create account metadata.
		ProducerPool p2p1 = null;
		try {
			p2p1 = (ProducerPool)getAppConfig()
				.getObject("AwsAccountServiceProducerPool");
			setAwsAccountServiceProducerPool(p2p1);
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
		String LOGTAG = getStepTag() + "[SetSrdExemptAccountProperty.run] ";
		logger.info(LOGTAG + "Begin running the step.");

		// Return properties
		addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);

		// Determine if we are allocating a new account or not.
		logger.info(LOGTAG + "Getting properties from preceding steps...");
		boolean allocateNewAccount = false;
		String accountId = null;

		String sAllocateNewAccount =
			getStepPropertyValue("DETERMINE_NEW_OR_EXISTING_ACCOUNT",
			"allocateNewAccount");
		logger.info(LOGTAG + "allocateNewAccount property is: " + sAllocateNewAccount);
		allocateNewAccount = Boolean.parseBoolean(sAllocateNewAccount);
		logger.info(LOGTAG + "allocateNewAccount is: " + allocateNewAccount);

		// If allocateNewAccount is false, set the srdExempt property to true.
		if (allocateNewAccount == false) {
			logger.info(LOGTAG + "allocateNewAccount is false, setting srdExempt to " +
				"true for this account.");

			// Get the VirtualPrivateCloudRequisition object.
		    VirtualPrivateCloudProvisioning vpcp = getVirtualPrivateCloudProvisioning();
		    VirtualPrivateCloudRequisition req = vpcp.getVirtualPrivateCloudRequisition();

		    // Get the AccountId from the requisition
		    accountId = req.getAccountId();
			logger.info(LOGTAG + "The accountId is: " + accountId);

			// Get a configured Account object and
			// AccountQuerySpecification from AppConfig.
			Account account = new Account();
			AccountQuerySpecification querySpec =
				new AccountQuerySpecification();
		    try {
		    	account = (Account)getAppConfig()
			    	.getObjectByType(account.getClass().getName());
		    	querySpec = (AccountQuerySpecification)getAppConfig()
				    	.getObjectByType(querySpec.getClass().getName());
		    }
		    catch (EnterpriseConfigurationObjectException ecoe) {
		    	String errMsg = "An error occurred retrieving an object from " +
		    	  "AppConfig. The exception is: " + ecoe.getMessage();
		    	logger.error(LOGTAG + errMsg);
		    	throw new StepException(errMsg, ecoe);
		    }

		    // Set the values of the querySpec.
		    try {
		    	querySpec.setAccountId(accountId);
		    }
		    catch (EnterpriseFieldException efe) {
		    	String errMsg = "An error occurred setting the values of the " +
		  	    	  "query spec. The exception is: " + efe.getMessage();
		  	    logger.error(LOGTAG + errMsg);
		  	    throw new StepException(errMsg, efe);
		    }

		    // Log the state of the querySpec.
		    try {
		    	logger.info(LOGTAG + "Account query spec: " +
		    		querySpec.toXmlString());
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
				rs = (RequestService)getAwsAccountServiceProducerPool()
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
				long queryStartTime = System.currentTimeMillis();
				results = account.query(querySpec, rs);
				long queryTime = System.currentTimeMillis() - queryStartTime;
				logger.info(LOGTAG + "Queried for account in " + queryTime +
					" ms.");
			}
			catch (EnterpriseObjectQueryException eoqe) {
				String errMsg = "An error occurred creating the object. " +
		    	  "The exception is: " + eoqe.getMessage();
		    	logger.error(LOGTAG + errMsg);
		    	throw new StepException(errMsg, eoqe);
			}
			finally {
				// Release the producer back to the pool
				getAwsAccountServiceProducerPool()
					.releaseProducer((MessageProducer)rs);
			}

			// If there is exactly one result, inspect the account.
			// If there is an account property srdExempt=false, then
			// set its value to true.
			boolean updatedPropValue = false;
			if (results.size() == 1) {
				account = (Account)results.get(0);
				List<Property> props = account.getProperty();
				ListIterator li = props.listIterator();
				while (li.hasNext()) {
					Property prop = (Property)li.next();
					if (prop.getKey().equalsIgnoreCase("srdExempt")) {
						try {
							prop.setValue("true");
						}
						catch (EnterpriseFieldException efe) {
							String errMsg = "An error occurred setting field " +
								"values. The exception is: " + efe.getMessage();
							logger.error(LOGTAG + errMsg);
							throw new StepException(errMsg, efe);
						}
						updatedPropValue = true;
					}
				}

				// If the property value was updated, update the
				// Account metadata. Otherwise there is nothing to do.
				if(updatedPropValue) {
					// Get a producer from the pool
					rs = null;
					try {
						rs = (RequestService)getAwsAccountServiceProducerPool()
							.getExclusiveProducer();
					}
					catch (JMSException jmse) {
						String errMsg = "An error occurred getting a producer " +
							"from the pool. The exception is: " + jmse.getMessage();
						logger.error(LOGTAG + errMsg);
						throw new StepException(errMsg, jmse);
					}

					try {
						long updateStartTime = System.currentTimeMillis();
						account.update(rs);
						long updateTime = System.currentTimeMillis() - updateStartTime;
						logger.info(LOGTAG + "Updated Account in " + updateTime +
							" ms.");
					}
					catch (EnterpriseObjectUpdateException eoue) {
						String errMsg = "An error occurred updating the object. " +
				    	  "The exception is: " + eoue.getMessage();
				    	logger.error(LOGTAG + errMsg);
				    	throw new StepException(errMsg, eoue);
					}
					finally {
						// Release the producer back to the pool
						getAwsAccountServiceProducerPool()
							.releaseProducer((MessageProducer)rs);
					}
				}
				else {
					logger.info(LOGTAG + "srdExempt was not true. " +
						"There is nothing to update.");
					addResultProperty("srdExempt", "attempted to update, " +
						"but nothing to update");
				}
			}
			else {
				String errMsg = "Invalid number of accounts returned. " +
					"Expected 1, got " + results.size();
				logger.error(LOGTAG + errMsg);
				throw new StepException(errMsg);
			}
		}
		// allocateNewAccount is true, so there is nothing to do for this step.
		else {
			logger.info(LOGTAG + "allocateNewAccount is true, so there is " +
				"nothing to do for this step.");
			addResultProperty("srdExempt", "no modification attempted");
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
			"[SetSrdExemptAccountProperty.simulate] ";
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
			"[SetSrdExemptAccountProperty.fail] ";
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
		String LOGTAG = getStepTag() + "[SetSrdExemptAccountPropert.rollback] ";
		logger.info(LOGTAG + "Rollback called, but this step has nothing to roll back.");
		update(ROLLBACK_STATUS, SUCCESS_RESULT);

		// Log completion time.
    	long time = System.currentTimeMillis() - startTime;
    	logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
	}

	private void setAwsAccountServiceProducerPool(ProducerPool pool) {
		m_awsAccountServiceProducerPool = pool;
	}

	private ProducerPool getAwsAccountServiceProducerPool() {
		return m_awsAccountServiceProducerPool;
	}

}
