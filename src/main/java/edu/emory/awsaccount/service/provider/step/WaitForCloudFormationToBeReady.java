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

import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudRequisition;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.producer.PointToPointProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.transport.RequestService;

import com.amazon.aws.moa.jmsobjects.cloudformation.v1_0.Stack;
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.VirtualPrivateCloudProvisioning;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.ProvisioningStep;
import com.amazon.aws.moa.objects.resources.v1_0.StackQuerySpecification;

import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;
import edu.emory.moa.jmsobjects.identity.v1_0.RoleAssignment;
import edu.emory.moa.objects.resources.v1_0.RoleAssignmentQuerySpecification;

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
public class WaitForCloudFormationToBeReady extends AbstractStep implements Step {

    private String m_stackName = null;
    private long m_maxWaitTimeInMillis = 60000;
    private long m_sleepTimeInMillis = 10000;
    private ProducerPool m_awsAccountServiceProducerPool = null;

    public void init (String provisioningId, Properties props,
            AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp)
            throws StepException {

        super.init(provisioningId, props, aConfig, vpcpp);

        String LOGTAG = getStepTag() + "[WaitForCloudFormationToBeReady.init] ";

        // This step needs to send messages to the AWS Account Service for
        // CloudFormation Stack requests. Specifically, it queries for a
        // stack to determine if CloudFormation is ready.
        try {
            ProducerPool p = (ProducerPool)getAppConfig().getObject("AwsAccountServiceProducerPool");
            setAwsAccountServiceProducerPool(p);
        }
        catch (EnterpriseConfigurationObjectException ecoe) {
            // An error occurred retrieving an object from AppConfig. Log it and throw an exception.
            String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + ecoe.getMessage();
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
        String LOGTAG = getStepTag() + "[WaitForCloudFormationToBeReady.run] ";
        logger.info(LOGTAG + "Begin running the step.");

        addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);

        VirtualPrivateCloudRequisition req = getVirtualPrivateCloudProvisioning().getVirtualPrivateCloudRequisition();

        String accountId = getStepPropertyValue("GENERATE_NEW_ACCOUNT", "newAccountId");
        if (accountId.equals(PROPERTY_VALUE_NOT_APPLICABLE) || accountId.equals(PROPERTY_VALUE_NOT_AVAILABLE)) {
            accountId = req.getAccountId();
            if (accountId == null || accountId.equals("")) {
                String errMsg = "No account number for the stack query can be found. Can't continue.";
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg);
            }
        }

        addResultProperty("maxWaitTimeInMillis", Long.toString(getMaxWaitTimeInMillis()));
        addResultProperty("sleepTimeInMillis", Long.toString(getSleepTimeInMillis()));

        // Get the current region we are working in.
        String region = req.getRegion();
        logger.info(LOGTAG + "The region specified in the requisition is: " + region);

        logger.info(LOGTAG + "Begin querying for a stack to see if CloudFormation is ready.");

        boolean isCloudFormationReady = false;
        int attempts = 0;
        while (!isCloudFormationReady && getQueryTimeInMillis(startTime) < getMaxWaitTimeInMillis()) {
            try {
                attempts++;
                logger.info(LOGTAG + "Attempting stack query attempt # " + attempts + ".");
                stackQuery(accountId, getStackName(), region);
                isCloudFormationReady = true;
                logger.info(LOGTAG + "Stack query was successful. CloudFormation is ready.");
            }
            catch (StepException se) {
                String errMsg = "An error occurred querying for the stack. to see if CloudFormation is ready. The exception is: " + se.getMessage();
                try {
                    Thread.sleep(10000);
                }
                catch (InterruptedException ie) {
                    String errMsg2 = "An error occurred sleeping before retrying the stack query. The exception is: " + ie.getMessage();
                    logger.error(LOGTAG + errMsg2);
                    throw new StepException(errMsg, ie);
                }
            }
        }

        // Add result properties
        addResultProperty("attempts", Integer.toString(attempts));
        addResultProperty("isCloudFormationReady", Boolean.toString(isCloudFormationReady));

        // Determine the step result
        String stepResult = null;
        // If CloudFormation is ready, this is a success.
        if (isCloudFormationReady == true) {
            stepResult = SUCCESS_RESULT;
        }
        // If CloudFormation is not ready, this is a failure.
        else {
            stepResult = FAILURE_RESULT;
        }

        // Update the step
        update(COMPLETED_STATUS, stepResult);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step run completed in " + time + "ms.");

        // Return the properties.
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
        long startTime = System.currentTimeMillis();

        super.rollback();

        String LOGTAG = getStepTag() + "[AuthorizeExistingAccountRequestor.rollback] ";
        logger.info(LOGTAG + "Rollback called, but this step has nothing to roll back.");
        update(ROLLBACK_STATUS, SUCCESS_RESULT);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
    }

    private List<Stack> stackQuery(String accountId, String stackName, String region) throws StepException {

        String LOGTAG = getStepTag() + "[WaitForCloudFormationToBeReady.stackQuery] ";

        logger.info(LOGTAG + "Performing a Stack.Query for accountId " + accountId + " stackName " + " and region " + region + ".");

        // Query the AWS Account Service for a Stack.
        // Get a configured Stack object and StackQuerySpecification from AppConfig
        Stack stack = new Stack();
        StackQuerySpecification querySpec = new StackQuerySpecification();
        try {
            stack = (Stack)getAppConfig().getObjectByType(stack.getClass().getName());
            querySpec = (StackQuerySpecification)getAppConfig().getObjectByType(querySpec.getClass().getName());
        }
        catch (EnterpriseConfigurationObjectException ecoe) {
            String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + ecoe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, ecoe);
        }

        // Set the values of the querySpec.
        try {
            querySpec.setAccountId(accountId);
            querySpec.setStackName(stackName);
            querySpec.setRegion(region);
        }
        catch (EnterpriseFieldException efe) {
            String errMsg = "An error occurred setting the values of the query specification object. The exception is: " + efe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, efe);
        }

        // Get a RequestService to use for this transaction.
        RequestService rs;
        try {
            rs = (RequestService)getAwsAccountServiceProducerPool().getExclusiveProducer();
        }
        catch (JMSException jmse) {
            String errMsg = "An error occurred getting a request service to use in this transaction. The exception is: " + jmse.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, jmse);
        }
        // Query for the Stack.
        try {
            long startTime = System.currentTimeMillis();
            @SuppressWarnings("unchecked")
            List<Stack> stacks = stack.query(querySpec, rs);
            long time = System.currentTimeMillis() - startTime;
            logger.info(LOGTAG + "Queried for stack for accountId " + accountId + " and stackName " + stackName
                    + "in " + time + " ms. Returned " + stacks.size() + " stack(s).");

            return stacks;
        }
        catch (EnterpriseObjectQueryException eoqe) {
            String errMsg = "An error occurred querying for the Stack object. The exception is: " + eoqe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, eoqe);
        }
        finally {
            getAwsAccountServiceProducerPool().releaseProducer((PointToPointProducer)rs);
        }
    }

    private void setAwsAccountServiceProducerPool(ProducerPool pool) {
        m_awsAccountServiceProducerPool = pool;
    }

    private ProducerPool getAwsAccountServiceProducerPool() {
        return m_awsAccountServiceProducerPool;
    }

    private void setStackName(String stackName) {
        m_stackName = stackName;
    }

    private String getStackName() {
        return m_stackName;
    }

    private void setMaxWaitTimeInMillis(long maxWaitTimeInMillis) {
        m_maxWaitTimeInMillis = maxWaitTimeInMillis;
    }

    private Long getMaxWaitTimeInMillis() {
        return m_maxWaitTimeInMillis;
    }

    private Long getSleepTimeInMillis() {
        return m_sleepTimeInMillis;
    }

    private long getQueryTimeInMillis(long queryStartTime) {
        return System.currentTimeMillis() - queryStartTime;
    }
}
