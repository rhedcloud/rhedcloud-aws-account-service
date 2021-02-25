/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2017 Emory University. All rights reserved.
 ******************************************************************************/
package edu.emory.awsaccount.service.deprovisioning.step;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.Account;
import com.amazon.aws.moa.objects.resources.v1_0.AccountQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import edu.emory.awsaccount.service.provider.AccountDeprovisioningProvider;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectDeleteException;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.transport.RequestService;

import javax.jms.JMSException;
import java.util.List;
import java.util.Properties;

/**
 * If there is account metadata, delete it.
 * <P>
 *
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 30 August 2018
 **/
public class DeleteAccountMetadata extends AbstractStep implements Step {

    private ProducerPool m_awsAccountServiceProducerPool = null;

    public void init (String provisioningId, Properties props,
            AppConfig aConfig, AccountDeprovisioningProvider vpcpp)
            throws StepException {

        super.init(provisioningId, props, aConfig, vpcpp);

        String LOGTAG = getStepTag() + "[DeleteAccountMetadata.init] ";

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
        String LOGTAG = getStepTag() + "[DeleteAccountMetadata.run] ";

        // Return properties
        addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);
        logger.info(LOGTAG + "Begin running the step.");

        boolean accountMetadataDeleted = false;

        // Get the accountId from the requisition
        String accountId = getAccountDeprovisioning()
            .getAccountDeprovisioningRequisition()
            .getAccountId();
        addResultProperty("accountId", accountId);

        // Query for the account
        // Get a configured account object and account query spec
        // from AppConfig.
        Account account = new Account();
        AccountQuerySpecification querySpec = new AccountQuerySpecification();
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

        // Set the values of the query spec
        try {
            querySpec.setAccountId(accountId);
        }
        catch (EnterpriseFieldException efe) {
            String errMsg = "An error occurred setting a field value. " +
                "The exception is: " + efe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException();
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

        // Query for the account metadata
        List results = null;
        try {
            long queryStartTime = System.currentTimeMillis();
            results = account.query(querySpec, rs);
            long createTime = System.currentTimeMillis() - queryStartTime;
            logger.info(LOGTAG + "Queried for Account in " + createTime +
                " ms. Got " + results.size() + " result(s).");
        }
        catch (EnterpriseObjectQueryException eoqe) {
            String errMsg = "An error occurred querying for the object. " +
              "The exception is: " + eoqe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, eoqe);
        }
        finally {
            // Release the producer back to the pool
            getAwsAccountServiceProducerPool()
                .releaseProducer((MessageProducer)rs);
        }

        // If there is a result, delete the account metadata
        if (results.size() > 0) {
            account = (Account)results.get(0);

            // Add the account name to the step properties.
            addResultProperty("accountName", account.getAccountName());

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

             // Delete the account metadata
             try {
                 long deleteStartTime = System.currentTimeMillis();
                 account.delete("Delete", rs);
                 long deleteTime = System.currentTimeMillis() - deleteStartTime;
                 logger.info(LOGTAG + "Deleted Account in " + deleteTime +
                     " ms. Got " + results.size() + " result(s).");
                 addResultProperty("deletedAccountMetadata", "true");
             }
             catch (EnterpriseObjectDeleteException eode) {
                 String errMsg = "An error occurred deleting the object. " +
                   "The exception is: " + eode.getMessage();
                 logger.error(LOGTAG + errMsg);
                 throw new StepException(errMsg, eode);
             }
             finally {
                 // Release the producer back to the pool
                 getAwsAccountServiceProducerPool()
                     .releaseProducer((MessageProducer)rs);
             }
        }
        else {
            String msg = "There is no account metadata for account " + accountId +
                ". There is nothing to delete.";
            logger.info(LOGTAG + msg);
            addResultProperty("deletedAccountMetadata", "false");
            addResultProperty("message", "msg");
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
            "[DeleteAccountMetadata.simulate] ";
        logger.info(LOGTAG + "Begin step simulation.");

        // Set return properties.
        addResultProperty("stepExecutionMethod", SIMULATED_EXEC_TYPE);
        addResultProperty("accountMetadataDeleted", "true");

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
            "[DeleteAccountMetadata.fail] ";
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
        String LOGTAG = getStepTag() + "[DeleteAccountMetadata.rollback] ";
        logger.info(LOGTAG + "Rollback called, nothing to roll back.");

        // Get the result props
        List<Property> props = getResultProperties();

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
