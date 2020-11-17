/* *****************************************************************************
 This file is part of the RHEDcloud AWS Account Service.

 Copyright (C) 2020 RHEDcloud Foundation. All rights reserved. 
 ******************************************************************************/

package edu.emory.awsaccount.service.provider;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.CustomRole;
import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.RoleProvisioning;
import com.amazon.aws.moa.objects.resources.v1_0.Datetime;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.RoleProvisioningQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.RoleProvisioningRequisition;
import com.amazon.aws.moa.objects.resources.v1_0.RoleProvisioningStep;
import edu.emory.awsaccount.service.RoleProvisioningRequestCommand;
import edu.emory.awsaccount.service.roleProvisioning.step.Step;
import edu.emory.awsaccount.service.roleProvisioning.step.StepException;
import org.apache.log4j.Logger;
import org.openeai.OpenEaiObject;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.config.PropertyConfig;
import org.openeai.jms.consumer.commands.CommandException;
import org.openeai.jms.producer.PointToPointProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectCreateException;
import org.openeai.moa.EnterpriseObjectDeleteException;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.EnterpriseObjectUpdateException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.threadpool.ThreadPool;
import org.openeai.threadpool.ThreadPoolException;
import org.openeai.transport.RequestService;
import org.openeai.utils.sequence.Sequence;
import org.openeai.utils.sequence.SequenceException;

import javax.jms.JMSException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/**
 * Custom AWS Role Provisioning provider.
 */
public class CustomAwsRoleProvisioningProvider extends OpenEaiObject implements RoleProvisioningProvider {
    private static final Logger logger = Logger.getLogger(RoleProvisioningRequestCommand.class);

    private static final String PROVISIONING_ID_SEQUENCE_NAME = "CustomRoleProvisioningIdSequence";

    private AppConfig appConfig;
    private boolean verbose;
    private String primedDocUrl;
    private Sequence provisioningIdSequence;
    private ProducerPool awsAccountServiceProducerPool;
    private ThreadPool threadPool;
    private int threadPoolSleepInterval = 1000;

    @Override
    public void init(AppConfig aConfig) throws ProviderException {
        final String LOGTAG = "[CustomAwsRoleProvisioningProvider.init] ";
        logger.info(LOGTAG + "Initializing...");
        setAppConfig(aConfig);

        // Get the provider properties
        try {
            PropertyConfig pConfig = (PropertyConfig) getAppConfig().getObject("CustomRoleProvisioningProviderProperties");
            setProperties(pConfig.getProperties());
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "Error retrieving a PropertyConfig object from AppConfig. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }

        logger.info(LOGTAG + getProperties().toString());

        // Set the verbose property.
        setVerbose(Boolean.parseBoolean(getProperties().getProperty("verbose", "false")));
        logger.info(LOGTAG + "Verbose property is: " + getVerbose());

        // Set the CentralAdminRoleDn property.
//        setCentralAdminRoleDn(getProperties().getProperty("centralAdminRoleDn", null));
//        logger.info(LOGTAG + "centralAdminRoleDn is: " + getCentralAdminRoleDn());

        // Set the primed doc URL for a template provisioning object.
        String primedDocumentUri = getProperties().getProperty("primedDocumentUri");
        if (primedDocumentUri == null) {
            String errMsg = "primedDocumentUri is null. It is a required property.";
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg);
        }
        setPrimedDocumentUrl(primedDocumentUri);
        logger.info(LOGTAG + "primedDocumentUrl property is: " + getPrimedDocumentUrl());

        // This provider needs a sequence to generate a unique ProvisioningId
        // for each transaction in multiple threads and multiple instances.
        try {
            Sequence seq = (Sequence) getAppConfig().getObject(PROVISIONING_ID_SEQUENCE_NAME);
            setProvisioningIdSequence(seq);
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "Error retrieving a Sequence object from AppConfig. The exception is: " + e.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new ProviderException(errMsg);
        }

        // This provider needs to send messages to the AWS account service to initialize provisioning transactions.
        try {
            ProducerPool pool = (ProducerPool) getAppConfig().getObject("AwsAccountServiceProducerPool");
            setAwsAccountServiceProducerPool(pool);
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "Error retrieving the AwsAccountServiceProducerPool object from AppConfig. The exception is: " + e.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new ProviderException(errMsg);
        }

        // This provider needs a thread pool in which to process concurrent provisioning transactions.
        try {
            ThreadPool tp = (ThreadPool) getAppConfig().getObject("CustomRoleProcessingThreadPool");
            setThreadPool(tp);
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "Error retrieving the CustomRoleProcessingThreadPool object from AppConfig. The exception is: " + e.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new ProviderException(errMsg);
        }

        // Initialize all provisioning steps this provider will use to
        // verify the runtime configuration as best we can.
        List<Properties> stepsAsProperties;
        try {
            stepsAsProperties = getStepsAsProperties();
            logger.info(LOGTAG + "There are " + stepsAsProperties.size() + " steps.");
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred getting ProvisioningStep properties from AppConfig. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }

        // For each property instantiate the step and log out its details.
        for (Properties sp : stepsAsProperties) {
            String className = sp.getProperty("className");
            String stepId = sp.getProperty("stepId");
            String stepType = sp.getProperty("type");
            if (className != null) {
                // Instantiate the step to verify that the given class exists in the classpath
                try {
                    logger.info(LOGTAG + "Step " + stepId + ": " + stepType);
                    Class.forName(className).newInstance();
                    logger.info(LOGTAG + "Verified class for step " + stepId + ": " + className);
                }
                catch (Exception e) {
                    String errMsg = "An error occurred instantiating a step. The exception is: " + e.getMessage();
                    logger.error(LOGTAG + errMsg);
                    throw new ProviderException(errMsg, e);
                }
            }
        }

        logger.info(LOGTAG + "Initialization complete.");
    }

    @Override
    public List<RoleProvisioning> query(RoleProvisioningQuerySpecification querySpec) throws ProviderException {
        final String LOGTAG = "[CustomAwsRoleProvisioningProvider.query] ";
        logger.info(LOGTAG + "Querying for RoleProvisioning with ProvisioningId: " + querySpec.getRoleProvisioningId());

        // Get a configured RoleProvisioning object to use.
        RoleProvisioning roleProvisioning;
        try {
            roleProvisioning = (RoleProvisioning) getAppConfig().getObjectByType(RoleProvisioning.class.getName());
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred getting RoleProvisioning properties from AppConfig. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }

        // Get a RequestService to use for this transaction.
        RequestService rs;
        try {
            rs = (RequestService) getAwsAccountServiceProducerPool().getExclusiveProducer();
            PointToPointProducer p2p = (PointToPointProducer) rs;
            p2p.setRequestTimeoutInterval(1000000);
        }
        catch (JMSException e) {
            String errMsg = "An error occurred getting a request service to use in this transaction. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }

        // Query the RoleProvisioning object.
        List<RoleProvisioning> results;
        try {
            logger.info(LOGTAG + "Querying for the RoleProvisioning...");
            long startTime = System.currentTimeMillis();
            results = roleProvisioning.query(querySpec, rs);
            long time = System.currentTimeMillis() - startTime;
            logger.info(LOGTAG + "Queried for RoleProvisioning objects in " + time + " ms.");
        }
        catch (EnterpriseObjectQueryException e) {
            String errMsg = "An error occurred querying the RoleProvisioning object The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }
        finally {
            getAwsAccountServiceProducerPool().releaseProducer((PointToPointProducer) rs);
        }

        return results;
    }

    @Override
    public RoleProvisioning generate(RoleProvisioningRequisition requisition) throws ProviderException {
        final String LOGTAG = "[CustomAwsRoleProvisioningProvider.generate] ";

        // Get a configured RoleProvisioning object from AppConfig
        RoleProvisioning roleProvisioning;
        try {
            roleProvisioning = (RoleProvisioning) appConfig.getObjectByType(RoleProvisioning.class.getName());
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred getting RoleProvisioning properties from AppConfig. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }

        // Get the next sequence number to identify the RoleProvisioning.
        String seq;
        try {
            seq = getProvisioningIdSequence().next();
            logger.info(LOGTAG + "The ProvisioningIdSequence value is: " + seq);
        }
        catch (SequenceException se) {
            String errMsg = "An error occurred getting the next value from the " + PROVISIONING_ID_SEQUENCE_NAME + " sequence. The exception is: " + se.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, se);
        }

        try {
            roleProvisioning.setRoleProvisioningId("custom-role-" + seq);
            roleProvisioning.setAccountId(requisition.getAccountId());
            roleProvisioning.setRoleProvisioningRequisition(requisition);
            roleProvisioning.setStatus(ROLE_PROVISIONING_STATUS_PENDING);
            roleProvisioning.setCreateUser("AwsAccountService");
            roleProvisioning.setCreateDatetime(new Datetime("Create", System.currentTimeMillis()));
        }
        catch (EnterpriseFieldException e) {
            String errMsg = "An error occurred setting the values of the RoleProvisioning object. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }

        List<Properties> stepsAsProperties;
        try {
            stepsAsProperties = getStepsAsProperties();
            logger.info(LOGTAG + "There are " + stepsAsProperties.size() + " steps.");
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred getting ProvisioningStep properties from AppConfig. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }

        long totalAnticipatedTime = 0;

        // instantiate the steps to prepare for execution
        for (Properties sp : stepsAsProperties) {
            String stepId = sp.getProperty("stepId");
            String stepType = sp.getProperty("type");
            String stepDesc = sp.getProperty("description");
            String stepAnticipatedTime = sp.getProperty("anticipatedTime");

            totalAnticipatedTime += Long.parseLong(stepAnticipatedTime);

            RoleProvisioningStep provisioningStep = roleProvisioning.newRoleProvisioningStep();
            try {
                provisioningStep.setRoleProvisioningId(roleProvisioning.getRoleProvisioningId());
                provisioningStep.setStepId(stepId);
                provisioningStep.setType(stepType);
                provisioningStep.setDescription(stepDesc);
                provisioningStep.setStatus(Step.STEP_STATUS_PENDING);
                provisioningStep.setAnticipatedTime(stepAnticipatedTime);
                provisioningStep.setCreateUser("AwsAccountService");
                provisioningStep.setCreateDatetime(new Datetime("Create", System.currentTimeMillis()));

                roleProvisioning.addRoleProvisioningStep(provisioningStep);
            }
            catch (EnterpriseFieldException e) {
                String errMsg = "An error occurred setting field values of the ProvisioningStep object. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new ProviderException(errMsg, e);
            }
        }

        // update the overall anticipated time, now that we've computed it
        try {
            roleProvisioning.setAnticipatedTime(Long.toString(totalAnticipatedTime));
        }
        catch (EnterpriseFieldException e) {
            String errMsg = "An error occurred setting field values of the provisioning object. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }

        // Create the RoleProvisioning.
        try {
            long createStartTime = System.currentTimeMillis();
            create(roleProvisioning);
            long createTime = System.currentTimeMillis() - createStartTime;
            logger.info(LOGTAG + "Created RoleProvisioning in " + createTime + " ms.");
        }
        catch (ProviderException e) {
            String errMsg = "An error occurred performing the RoleProvisioning create. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }

        // Add the provisioning request to the ThreadPool for processing.
        // If this thread pool is set to check for available threads before
        // adding jobs to the pool, it may throw an exception indicating it
        // is busy when we try to add a job. We need to catch that exception
        // and try to add the job until we are successful.
        RoleProvisioningTransaction roleProvisioningTransaction = new RoleProvisioningTransaction(roleProvisioning);
        boolean jobAdded = false;
        while (!jobAdded) {
            try {
                logger.info(LOGTAG + "Adding job to thread pool for ProvisioningId: " + roleProvisioning.getRoleProvisioningId());
                getThreadPool().addJob(roleProvisioningTransaction);
                jobAdded = true;
            }
            catch (ThreadPoolException e) {
                logger.debug(LOGTAG + "The thread pool is busy. Sleeping for " + getThreadPoolSleepInterval() + " milliseconds.");
                try {
                    Thread.sleep(getThreadPoolSleepInterval());
                }
                catch (InterruptedException ie) {
                    String errMsg = "An error occurred while sleeping to allow threads in the pool to clear for processing. The exception is " + ie.getMessage();
                    logger.fatal(LOGTAG + errMsg);
                    throw new ProviderException(errMsg);
                }
            }
        }

        return roleProvisioning;
    }

    @Override
    public void create(RoleProvisioning roleProvisioning) throws ProviderException {
        final String LOGTAG = "[CustomAwsRoleProvisioningProvider.create] ";

        // Get a RequestService to use for this transaction.
        RequestService rs;
        try {
            rs = (RequestService) getAwsAccountServiceProducerPool().getExclusiveProducer();
        }
        catch (JMSException e) {
            String errMsg = "An error occurred getting a request service to use in this transaction. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }

        // Create the RoleProvisioning object.
        try {
            long startTime = System.currentTimeMillis();
            roleProvisioning.create(rs);
            long time = System.currentTimeMillis() - startTime;
            logger.info(LOGTAG + "Created RoleProvisioning object in " + time + " ms.");
        }
        catch (EnterpriseObjectCreateException e) {
            String errMsg = "An error occurred creating the RoleProvisioning object. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }
        finally {
            getAwsAccountServiceProducerPool().releaseProducer((PointToPointProducer)rs);
        }
    }

    private void create(CustomRole customRole) throws ProviderException {
        final String LOGTAG = "[CustomAwsRoleProvisioningProvider.createCustomRole] ";

        // Get a RequestService to use for this transaction.
        RequestService rs;
        try {
            rs = (RequestService) getAwsAccountServiceProducerPool().getExclusiveProducer();
        }
        catch (JMSException e) {
            String errMsg = "An error occurred getting a request service to use in this transaction. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }

        // Create the CustomRole object.
        try {
            long startTime = System.currentTimeMillis();
            customRole.create(rs);
            long time = System.currentTimeMillis() - startTime;
            logger.info(LOGTAG + "Created CustomRole object in " + time + " ms.");
        }
        catch (EnterpriseObjectCreateException e) {
            String errMsg = "An error occurred creating the CustomRole object. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }
        finally {
            getAwsAccountServiceProducerPool().releaseProducer((PointToPointProducer)rs);
        }
    }

    @Override
    public void update(RoleProvisioning roleProvisioning) throws ProviderException {
        final String LOGTAG = "[CustomAwsRoleProvisioningProvider.update] ";

        // Get a RequestService to use for this transaction.
        RequestService rs;
        try {
            rs = (RequestService) getAwsAccountServiceProducerPool().getExclusiveProducer();
        }
        catch (JMSException e) {
            String errMsg = "An error occurred getting a request service to use in this transaction. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }

        // Create the RoleProvisioning object.
        try {
            long startTime = System.currentTimeMillis();
            roleProvisioning.update(rs);
            long time = System.currentTimeMillis() - startTime;
            logger.info(LOGTAG + "Updated RoleProvisioning object in " + time + " ms.");
        }
        catch (EnterpriseObjectUpdateException e) {
            String errMsg = "An error occurred updating the RoleProvisioning object. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }
        finally {
            getAwsAccountServiceProducerPool().releaseProducer((PointToPointProducer)rs);
        }
    }

    @Override
    public void delete(RoleProvisioning roleProvisioning) throws ProviderException {
        final String LOGTAG = "[CustomAwsRoleProvisioningProvider.delete] ";

        // Get a RequestService to use for this transaction.
        RequestService rs;
        try {
            rs = (RequestService) getAwsAccountServiceProducerPool().getExclusiveProducer();
        }
        catch (JMSException e) {
            String errMsg = "An error occurred getting a request service to use in this transaction. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }

        // Delete the RoleProvisioning object.
        try {
            long startTime = System.currentTimeMillis();
            roleProvisioning.delete("Delete", rs);
            long time = System.currentTimeMillis() - startTime;
            logger.info(LOGTAG + "Deleted RoleProvisioning object in " + time + " ms.");
        }
        catch (EnterpriseObjectDeleteException e) {
            String errMsg = "An error occurred deleting the RoleProvisioning object. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }
        finally {
            getAwsAccountServiceProducerPool().releaseProducer((PointToPointProducer)rs);
        }
    }

    private List<Properties> getStepsAsProperties() throws EnterpriseConfigurationObjectException {
        List<Properties> stepsAsProperties = new ArrayList<>();

        // Get a list of all steps as properties.
        @SuppressWarnings("unchecked")
        List<PropertyConfig> stepPropConfigs = getAppConfig().getObjectsLike("ProvisioningStep");
        // Convert property configs to properties
        for (PropertyConfig stepConfig : stepPropConfigs) {
            Properties stepProp = stepConfig.getProperties();
            stepsAsProperties.add(stepProp);
        }
        // Sort the list by stepId
        stepsAsProperties.sort(Comparator.comparing((Properties p) -> Integer.valueOf(p.getProperty("stepId"))));
        return stepsAsProperties;
    }

    private AppConfig getAppConfig() { return  appConfig; }
    private void setAppConfig(AppConfig v) { this.appConfig = v; }
    private boolean getVerbose() { return verbose; }
    private void setVerbose(boolean v) { this.verbose = v; }
    private String getPrimedDocumentUrl() { return primedDocUrl; }
    private void setPrimedDocumentUrl(String v) { this.primedDocUrl = v; }
    public Sequence getProvisioningIdSequence() { return provisioningIdSequence; }
    public void setProvisioningIdSequence(Sequence v) { this.provisioningIdSequence = v; }
    public ProducerPool getAwsAccountServiceProducerPool() { return awsAccountServiceProducerPool; }
    public void setAwsAccountServiceProducerPool(ProducerPool v) { this.awsAccountServiceProducerPool = v; }
    public ThreadPool getThreadPool() { return threadPool; }
    public void setThreadPool(ThreadPool v) { this.threadPool = v; }
    public int getThreadPoolSleepInterval() { return threadPoolSleepInterval; }
    public RoleProvisioningProvider getRoleProvisioningProvider() { return this; }


    /**
     * A transaction to process custom AWS role provisioning.
     */
    private class RoleProvisioningTransaction implements java.lang.Runnable {
        private RoleProvisioning roleProvisioning;
        private long executionStartTime = 0;

        public RoleProvisioningTransaction(RoleProvisioning roleProvisioning) {
            // must happen first
            setRoleProvisioning(roleProvisioning);

            final String LOGTAG = "[RoleProvisioningTransaction{" + getProvisioningId() + "}] ";
            logger.info(LOGTAG + "Initializing provisioning process");
        }

        public void run() {
            setExecutionStartTime(System.currentTimeMillis());

            String LOGTAG = "[RoleProvisioningTransaction{" + getProvisioningId() + "}] ";
            logger.info(LOGTAG + "Running provisioning process");

            List<Properties> stepsAsProperties;
            try {
                stepsAsProperties = getStepsAsProperties();
                logger.info(LOGTAG + "There are " + stepsAsProperties.size() + " steps.");
            }
            catch (EnterpriseConfigurationObjectException e) {
                String errMsg = "An error occurred getting ProvisioningStep properties from AppConfig. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                return;
            }

            // For each property instantiate the step, call the execute
            // method, and if successful, place it in the map of completed steps.
            List<Step> completedSteps = new ArrayList<>();
            int stepIndex = 0;
            for (Properties props : stepsAsProperties) {
                stepIndex++;
                String className = props.getProperty("className");
                if (className != null) {
                    // Instantiate the step
                    Step step = null;
                    try {
                        Class<?> stepClass = Class.forName(className);
                        step = (Step) stepClass.newInstance();
                        logger.info(LOGTAG + "Initializing step index " + stepIndex + ".");
                        step.init(getProvisioningId(), props, getAppConfig(), getRoleProvisioningProvider());
                    }
                    catch (ClassNotFoundException | IllegalAccessException | InstantiationException e) {
                        String errMsg = "An error occurred instantiating the Step. The exception is: " + e.getMessage();
                        logger.error(LOGTAG + errMsg);
                        rollbackCompletedSteps(completedSteps);
                        return;
                    }
                    catch (StepException se) {
                        String errMsg = "An error occurred initializing step " + step.getStepId() + ". The exception is: " + se.getMessage();
                        logger.error(LOGTAG + errMsg);
                        try {
                            // Add an error step property limited to 255 characters (database column size)
                            String m = se.getMessage().substring(0, Math.min(se.getMessage().length(), 254));
                            step.addResultProperty("stepExecutionException", m);
                            step.update(Step.STEP_STATUS_COMPLETED, Step.STEP_RESULT_FAILURE);
                            logger.info(LOGTAG + "Updated to completed status and failure result.");
                        }
                        catch (StepException se2) {
                            String errMsg2 = "An error occurred updating the status to indicate failure. The exception is: " + se2.getMessage();
                            logger.error(LOGTAG + errMsg2);
                        }
                        rollbackCompletedSteps(completedSteps);
                        return;
                    }

                    // Execute the step
                    try {
                        logger.info(LOGTAG + "Executing [Step-" + step.getStepId() + "] " + step.getDescription());
                        long startTime = System.currentTimeMillis();
                        List<Property> resultProps = step.execute();
                        long time = System.currentTimeMillis() - startTime;
                        logger.info(LOGTAG + "Completed [Step-" + step.getStepId() + "] with result " + step.getResult() + " in " + time + " ms"
                                + " and result properties " + resultPropsToXmlString(resultProps));

                        // If the result of the step is failure, roll back all completed steps and return.
                        if (step.getResult().equals(Step.STEP_RESULT_FAILURE)) {
                            logger.info(LOGTAG + "[Step " + step.getStepId() + "] failed. Rolling back all completed steps.");
                            rollbackCompletedSteps(completedSteps);
                            return;
                        }

                        // Add all successfully completed steps to the list of completed steps.
                        completedSteps.add(step);
                    }
                    catch (StepException se) {
                        // An error occurred executing the step.
                        // Log it and roll back all preceding steps.
                        LOGTAG = LOGTAG + "[StepExecutionException][Step-" + step.getStepId() + "] ";
                        String errMsg = "The exception is: " + se.getMessage();
                        logger.error(LOGTAG + errMsg);

                        try {
                            logger.info(LOGTAG + "Setting completed status, failure result, and final error details...");
                            String m = se.getMessage().substring(0, Math.min(se.getMessage().length(), 254));
                            step.addResultProperty("stepExecutionException", m);
                            step.update(Step.STEP_STATUS_COMPLETED, Step.STEP_RESULT_FAILURE);
                            logger.info(LOGTAG + "Updated to completed status and failure result.");
                        }
                        catch (StepException se2) {
                            String errMsg2 = "An error occurred updating the status to indicate failure. The exception is: " + se2.getMessage();
                            logger.error(LOGTAG + errMsg2);
                        }
                        finally {
                            rollbackCompletedSteps(completedSteps);
                        }
                        return;
                    }
                }
                else {
                    String errMsg = "An error occurred instantiating a step. The className property is null.";
                    logger.error(LOGTAG + errMsg);
                    rollbackCompletedSteps(completedSteps);
                    return;
                }
            }

            // All steps completed successfully.
            // Set the end of execution.
            long executionTime = System.currentTimeMillis() - getExecutionStartTime();

            // Update the state of the RoleProvisioning object in this transaction.
            queryForRoleProvisioningBaseline();

            // Set the status to complete, the result to success, and the
            // execution time.
            try {
                getRoleProvisioning().setStatus(ROLE_PROVISIONING_STATUS_COMPLETED);
                getRoleProvisioning().setProvisioningResult(ROLE_PROVISIONING_RESULT_SUCCESS);
                getRoleProvisioning().setActualTime(Long.toString(executionTime));
            }
            catch (EnterpriseFieldException efe) {
                String errMsg = "An error occurred setting field values on the RoleProvisioning object. The exception is: " + efe.getMessage();
                logger.error(LOGTAG + errMsg);
                return;
            }

            // Update the RoleProvisioning object.
            try {
                getRoleProvisioningProvider().update(getRoleProvisioning());
            }
            catch (ProviderException e) {
                String errMsg = "An error occurred querying for the  current state of a RoleProvisioning object. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                return;
            }
        }

        private void rollbackCompletedSteps(List<Step> completedSteps) {
            String LOGTAG = "[RoleProvisioningTransaction{" + getProvisioningId() + "}] ";
            logger.info(LOGTAG + "Starting rollback of completed steps...");

            // Reverse the order of the completedSteps list.
            completedSteps.sort(Comparator.comparing((Step s) -> Integer.valueOf(s.getStepId())).reversed());

            long startTime = System.currentTimeMillis();
            for (Step completedStep : completedSteps) {
                try {
                    completedStep.rollback();
                }
                catch (StepException e) {
                    String errMsg = "An error occurred rolling back step " + completedStep.getStepId() + ": " + completedStep.getType() + ". The exception is: " + e.getMessage();
                    logger.error(LOGTAG + errMsg);
                }
            }
            long time = System.currentTimeMillis() - startTime;
            logger.info(LOGTAG + "Provisioning rollback complete in " + time + " ms.");

            // All steps completed successfully. Set the end of execution.
            long executionTime = System.currentTimeMillis() - getExecutionStartTime();

            // Update the state of the RoleProvisioning object in this transaction.
            queryForRoleProvisioningBaseline();

            // Set the status to complete, the result to failure, and the execution time.
            try {
                getRoleProvisioning().setStatus(ROLE_PROVISIONING_STATUS_COMPLETED);
                getRoleProvisioning().setProvisioningResult(ROLE_PROVISIONING_RESULT_FAILURE);
                getRoleProvisioning().setActualTime(Long.toString(executionTime));
            }
            catch (EnterpriseFieldException efe) {
                String errMsg = "An error setting field values on the RoleProvisioning object. The exception is: " + efe.getMessage();
                logger.error(LOGTAG + errMsg);
            }

            // Update the RoleProvisioning object.
            try {
                getRoleProvisioningProvider().update(getRoleProvisioning());
            }
            catch (ProviderException e) {
                String errMsg = "An error occurred querying for the  current state of a RoleProvisioning object. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
            }

            // The the provider is configured to create an incident
            // in ServiceNow upon failure, create an incident.
            if (false) {
                logger.info(LOGTAG + "Creating an Incident in ServiceNow...");
                //TODO: create an incident.
            }
            else {
                logger.info(LOGTAG + "createIncidentOnFailure is false. Will not create an incident in ServiceNow.");
            }
        }

        private String resultPropsToXmlString(List<Property> resultProps) {
            // could use toXmlString() on each Property but that could throw an exception which is otherwise avoidable
            // so hand code serialization to XML since it's just for log messages
            StringBuilder buf = new StringBuilder();
            for (Property p : resultProps) {
                buf.append("<Property>");
                buf.append("<Key>").append(p.getKey()).append("</Key>");
                buf.append("<Value>").append(p.getValue()).append("</Value>");
                buf.append("</Property>");
            }
            return buf.toString();
        }

        private void queryForRoleProvisioningBaseline() {
            String LOGTAG = "[RoleProvisioningTransaction{" + getProvisioningId() + "}] ";

            // Query for the RoleProvisioning object in the AWS Account Service.
            // Get a configured query spec from AppConfig
            RoleProvisioningQuerySpecification qs = new RoleProvisioningQuerySpecification();
            try {
                qs = (RoleProvisioningQuerySpecification) getAppConfig().getObjectByType(RoleProvisioningQuerySpecification.class.getName());
            }
            catch (EnterpriseConfigurationObjectException e) {
                String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
            }

            // Set the values of the query spec.
            try {
                qs.setRoleProvisioningId(getProvisioningId());
            }
            catch (EnterpriseFieldException efe) {
                String errMsg = "An error occurred setting the values of the RoleProvisioning query spec. The exception is: " + efe.getMessage();
                logger.error(LOGTAG + errMsg);
            }

            // Log the state of the query spec.
            try {
                logger.info(LOGTAG + "Query spec is: " + qs.toXmlString());
            }
            catch (XmlEnterpriseObjectException e) {
                String errMsg = "An error occurred serializing the query spec to XML. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
            }

            try {
                List<RoleProvisioning> results = getRoleProvisioningProvider().query(qs);
                setRoleProvisioning(results.get(0));
            }
            catch (ProviderException pe) {
                String errMsg = "An error occurred querying for the  current state of a RoleProvisioning object. The exception is: " + pe.getMessage();
                logger.error(LOGTAG + errMsg);
            }
        }

        private RoleProvisioning getRoleProvisioning() { return roleProvisioning; }
        private void setRoleProvisioning(RoleProvisioning v) { roleProvisioning = v; }
        private String getProvisioningId() { return roleProvisioning.getRoleProvisioningId(); }

        private long getExecutionStartTime() { return executionStartTime; }
        private void setExecutionStartTime(long time) { executionStartTime = time; }
    }
}