/* *****************************************************************************
 This file is part of the RHEDcloud AWS Account Service.

 Copyright (C) 2020 RHEDcloud Foundation. All rights reserved.
 ******************************************************************************/

package edu.emory.awsaccount.service.provider;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.RoleDeprovisioning;
import com.amazon.aws.moa.objects.resources.v1_0.Datetime;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.RoleDeprovisioningQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.RoleDeprovisioningRequisition;
import com.amazon.aws.moa.objects.resources.v1_0.RoleDeprovisioningStep;
import edu.emory.awsaccount.service.roleDeprovisioning.step.Step;
import edu.emory.awsaccount.service.roleDeprovisioning.step.StepException;
import org.apache.log4j.Logger;
import org.openeai.OpenEaiObject;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.config.PropertyConfig;
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
 * Custom AWS Role Deprovisioning provider.
 */
public class CustomAwsRoleDeprovisioningProvider extends OpenEaiObject implements RoleDeprovisioningProvider {
    private static final Logger logger = Logger.getLogger(CustomAwsRoleDeprovisioningProvider.class);

    private static final String PROVISIONING_ID_SEQUENCE_NAME = "CustomRoleDeprovisioningIdSequence";
    private static final String PROVISIONING_ID_DEFAULT_PREFIX = "custom-role-deprovisioning-";

    private AppConfig appConfig;
    private boolean verbose;
    private Sequence provisioningIdSequence;
    private String provisioningIdPrefix;
    private ProducerPool awsAccountServiceProducerPool;
    private ThreadPool threadPool;
    private int threadPoolSleepInterval;

    @Override
    public void init(AppConfig aConfig) throws ProviderException {
        final String LOGTAG = "[CustomAwsRoleDeprovisioningProvider.init] ";
        logger.info(LOGTAG + "Initializing...");
        setAppConfig(aConfig);

        // Get the provider properties
        try {
            PropertyConfig pConfig = (PropertyConfig) getAppConfig().getObject("CustomRoleDeprovisioningProviderProperties");
            setProperties(pConfig.getProperties());
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "Error retrieving a PropertyConfig object from AppConfig. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }

        logger.info(LOGTAG + getProperties().toString());

        // Set the properties for the provider
        setVerbose(Boolean.parseBoolean(getProperties().getProperty("verbose", "false")));
        logger.info(LOGTAG + "verbose property is: " + getVerbose());
        setProvisioningIdPrefix(getProperties().getProperty("roleDeprovisioningIdPrefix", PROVISIONING_ID_DEFAULT_PREFIX));
        logger.info(LOGTAG + "roleDeprovisioningIdPrefix property is: " + getProvisioningIdPrefix());

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
        setThreadPoolSleepInterval(Integer.parseInt(getProperties().getProperty("threadPoolSleepInterval", "1000")));
        logger.info(LOGTAG + "threadPoolSleepInterval property is: " + getThreadPoolSleepInterval());

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
    public List<RoleDeprovisioning> query(RoleDeprovisioningQuerySpecification querySpec) throws ProviderException {
        final String LOGTAG = "[CustomAwsRoleDeprovisioningProvider.query] ";
        logger.info(LOGTAG + "Querying for RoleDeprovisioning with ProvisioningId: " + querySpec.getRoleDeprovisioningId());

        // Get a configured object to use.
        RoleDeprovisioning roleDeprovisioning;
        try {
            roleDeprovisioning = (RoleDeprovisioning) getAppConfig().getObjectByType(RoleDeprovisioning.class.getName());
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred getting RoleDeprovisioning properties from AppConfig. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }

        // Get a RequestService to use for this transaction.
        RequestService rs;
        try {
            rs = (RequestService) getAwsAccountServiceProducerPool().getExclusiveProducer();
            PointToPointProducer p2p = (PointToPointProducer) rs;
            p2p.setRequestTimeoutInterval(1_000_000);
        }
        catch (JMSException e) {
            String errMsg = "An error occurred getting a request service to use in this transaction. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }

        List<RoleDeprovisioning> results;
        try {
            logger.info(LOGTAG + "Querying for the RoleDeprovisioning...");
            long startTime = System.currentTimeMillis();
            results = roleDeprovisioning.query(querySpec, rs);
            long time = System.currentTimeMillis() - startTime;
            logger.info(LOGTAG + "Queried for RoleDeprovisioning objects in " + time + " ms.");
        }
        catch (EnterpriseObjectQueryException e) {
            String errMsg = "An error occurred querying the RoleDeprovisioning object The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }
        finally {
            getAwsAccountServiceProducerPool().releaseProducer((PointToPointProducer) rs);
        }

        return results;
    }

    @Override
    public RoleDeprovisioning generate(RoleDeprovisioningRequisition requisition) throws ProviderException {
        final String LOGTAG = "[CustomAwsRoleDeprovisioningProvider.generate] ";

        // Get a configured object from AppConfig
        RoleDeprovisioning roleDeprovisioning;
        try {
            roleDeprovisioning = (RoleDeprovisioning) appConfig.getObjectByType(RoleDeprovisioning.class.getName());
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred getting RoleDeprovisioning properties from AppConfig. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }

        // Get the next sequence number to identify the provisioning.
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

        String provisioningId = getProvisioningIdPrefix() + seq;
        try {
            roleDeprovisioning.setRoleDeprovisioningId(provisioningId);
            roleDeprovisioning.setAccountId(requisition.getAccountId());
            roleDeprovisioning.setRoleDeprovisioningRequisition(requisition);
            roleDeprovisioning.setStatus(ROLE_PROVISIONING_STATUS_PENDING);
            roleDeprovisioning.setCreateUser("AwsAccountService");
            roleDeprovisioning.setCreateDatetime(new Datetime("Create", System.currentTimeMillis()));
        }
        catch (EnterpriseFieldException e) {
            String errMsg = "An error occurred setting the values of the RoleDeprovisioning object. The exception is: " + e.getMessage();
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

            RoleDeprovisioningStep provisioningStep = roleDeprovisioning.newRoleDeprovisioningStep();
            try {
                provisioningStep.setRoleDeprovisioningId(provisioningId);
                provisioningStep.setStepId(stepId);
                provisioningStep.setType(stepType);
                provisioningStep.setDescription(stepDesc);
                provisioningStep.setStatus(Step.STEP_STATUS_PENDING);
                provisioningStep.setAnticipatedTime(stepAnticipatedTime);
                provisioningStep.setCreateUser("AwsAccountService");
                provisioningStep.setCreateDatetime(new Datetime("Create", System.currentTimeMillis()));

                roleDeprovisioning.addRoleDeprovisioningStep(provisioningStep);
            }
            catch (EnterpriseFieldException e) {
                String errMsg = "An error occurred setting field values of the ProvisioningStep object. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new ProviderException(errMsg, e);
            }
        }

        // update the overall anticipated time, now that we've computed it
        try {
            roleDeprovisioning.setAnticipatedTime(Long.toString(totalAnticipatedTime));
        }
        catch (EnterpriseFieldException e) {
            String errMsg = "An error occurred setting field values of the provisioning object. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }

        try {
            long createStartTime = System.currentTimeMillis();
            create(roleDeprovisioning);
            long createTime = System.currentTimeMillis() - createStartTime;
            logger.info(LOGTAG + "Created RoleDeprovisioning in " + createTime + " ms.");
        }
        catch (ProviderException e) {
            String errMsg = "An error occurred performing the RoleDeprovisioning create. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }

        // Add the provisioning request to the ThreadPool for processing.
        // If this thread pool is set to check for available threads before
        // adding jobs to the pool, it may throw an exception indicating it
        // is busy when we try to add a job. We need to catch that exception
        // and try to add the job until we are successful.
        RoleDeprovisioningTransaction roleDeprovisioningTransaction = new RoleDeprovisioningTransaction(roleDeprovisioning);
        boolean jobAdded = false;
        while (!jobAdded) {
            try {
                logger.info(LOGTAG + "Adding job to thread pool for ProvisioningId: " + provisioningId);
                getThreadPool().addJob(roleDeprovisioningTransaction);
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

        return roleDeprovisioning;
    }

    @Override
    public void create(RoleDeprovisioning roleDeprovisioning) throws ProviderException {
        final String LOGTAG = "[CustomAwsRoleDeprovisioningProvider.create] ";

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

        try {
            long startTime = System.currentTimeMillis();
            roleDeprovisioning.create(rs);
            long time = System.currentTimeMillis() - startTime;
            logger.info(LOGTAG + "Created RoleDeprovisioning object in " + time + " ms.");
        }
        catch (EnterpriseObjectCreateException e) {
            String errMsg = "An error occurred creating the RoleDeprovisioning object. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }
        finally {
            getAwsAccountServiceProducerPool().releaseProducer((PointToPointProducer)rs);
        }
    }

    @Override
    public void update(RoleDeprovisioning roleDeprovisioning) throws ProviderException {
        final String LOGTAG = "[CustomAwsRoleDeprovisioningProvider.update] ";

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

        try {
            long startTime = System.currentTimeMillis();
            roleDeprovisioning.update(rs);
            long time = System.currentTimeMillis() - startTime;
            logger.info(LOGTAG + "Updated RoleDeprovisioning object in " + time + " ms.");
        }
        catch (EnterpriseObjectUpdateException e) {
            String errMsg = "An error occurred updating the RoleDeprovisioning object. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new ProviderException(errMsg, e);
        }
        finally {
            getAwsAccountServiceProducerPool().releaseProducer((PointToPointProducer)rs);
        }
    }

    @Override
    public void delete(RoleDeprovisioning roleDeprovisioning) throws ProviderException {
        final String LOGTAG = "[CustomAwsRoleDeprovisioningProvider.delete] ";

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

        try {
            long startTime = System.currentTimeMillis();
            roleDeprovisioning.delete("Delete", rs);
            long time = System.currentTimeMillis() - startTime;
            logger.info(LOGTAG + "Deleted RoleDeprovisioning object in " + time + " ms.");
        }
        catch (EnterpriseObjectDeleteException e) {
            String errMsg = "An error occurred deleting the RoleDeprovisioning object. The exception is: " + e.getMessage();
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

    private AppConfig getAppConfig() { return appConfig; }
    private void setAppConfig(AppConfig v) { this.appConfig = v; }
    public ProducerPool getAwsAccountServiceProducerPool() { return awsAccountServiceProducerPool; }
    private void setAwsAccountServiceProducerPool(ProducerPool v) { this.awsAccountServiceProducerPool = v; }

    private boolean getVerbose() { return verbose; }
    private void setVerbose(boolean v) { this.verbose = v; }
    public Sequence getProvisioningIdSequence() { return provisioningIdSequence; }
    public void setProvisioningIdSequence(Sequence v) { this.provisioningIdSequence = v; }
    public String getProvisioningIdPrefix() { return provisioningIdPrefix; }
    public void setProvisioningIdPrefix(String v) { this.provisioningIdPrefix = v; }
    public ThreadPool getThreadPool() { return threadPool; }
    public void setThreadPool(ThreadPool v) { this.threadPool = v; }
    public int getThreadPoolSleepInterval() { return threadPoolSleepInterval; }
    public void setThreadPoolSleepInterval(int v) { this.threadPoolSleepInterval = v; }
    public RoleDeprovisioningProvider getRoleDeprovisioningProvider() { return this; }


    /**
     * A transaction to process custom AWS role deprovisioning.
     */
    private class RoleDeprovisioningTransaction implements java.lang.Runnable {
        private RoleDeprovisioning roleDeprovisioning;
        private long executionStartTime = 0;

        public RoleDeprovisioningTransaction(RoleDeprovisioning roleDeprovisioning) {
            // must happen first
            setRoleDeprovisioning(roleDeprovisioning);

            final String LOGTAG = "[RoleDeprovisioningTransaction{" + getProvisioningId() + "}] ";
            logger.info(LOGTAG + "Initializing provisioning process");
        }

        public void run() {
            setExecutionStartTime(System.currentTimeMillis());

            String LOGTAG = "[RoleDeprovisioningTransaction{" + getProvisioningId() + "}] ";
            logger.info(LOGTAG + "Running deprovisioning transaction");

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
                        step.init(getProvisioningId(), props, getAppConfig(), getRoleDeprovisioningProvider());
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

            // Update the state of the RoleDeprovisioning object in this transaction.
            queryForRoleDeprovisioningBaseline();

            // Set the status to complete, the result to success, and the
            // execution time.
            try {
                getRoleDeprovisioning().setStatus(ROLE_PROVISIONING_STATUS_COMPLETED);
                getRoleDeprovisioning().setDeprovisioningResult(ROLE_PROVISIONING_RESULT_SUCCESS);
                getRoleDeprovisioning().setActualTime(Long.toString(executionTime));
            }
            catch (EnterpriseFieldException efe) {
                String errMsg = "An error occurred setting field values on the RoleDeprovisioning object. The exception is: " + efe.getMessage();
                logger.error(LOGTAG + errMsg);
                return;
            }

            // Update the RoleDeprovisioning object.
            try {
                getRoleDeprovisioningProvider().update(getRoleDeprovisioning());
            }
            catch (ProviderException e) {
                String errMsg = "An error occurred querying for the  current state of a RoleDeprovisioning object. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                return;
            }

            logger.info(LOGTAG + "Completed deprovisioning transaction");
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

            // Update the state of the RoleDeprovisioning object in this transaction.
            queryForRoleDeprovisioningBaseline();

            // Set the status to complete, the result to failure, and the execution time.
            try {
                getRoleDeprovisioning().setStatus(ROLE_PROVISIONING_STATUS_COMPLETED);
                getRoleDeprovisioning().setDeprovisioningResult(ROLE_PROVISIONING_RESULT_FAILURE);
                getRoleDeprovisioning().setActualTime(Long.toString(executionTime));
            }
            catch (EnterpriseFieldException efe) {
                String errMsg = "An error setting field values on the RoleDeprovisioning object. The exception is: " + efe.getMessage();
                logger.error(LOGTAG + errMsg);
            }

            // Update the RoleDeprovisioning object.
            try {
                getRoleDeprovisioningProvider().update(getRoleDeprovisioning());
            }
            catch (ProviderException e) {
                String errMsg = "An error occurred querying for the  current state of a RoleDeprovisioning object. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
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

        private void queryForRoleDeprovisioningBaseline() {
            String LOGTAG = "[RoleDeprovisioningTransaction{" + getProvisioningId() + "}] ";

            // Query for the RoleDeprovisioning object in the AWS Account Service.
            // Get a configured query spec from AppConfig
            RoleDeprovisioningQuerySpecification qs = new RoleDeprovisioningQuerySpecification();
            try {
                qs = (RoleDeprovisioningQuerySpecification) getAppConfig().getObjectByType(RoleDeprovisioningQuerySpecification.class.getName());
            }
            catch (EnterpriseConfigurationObjectException e) {
                String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
            }

            // Set the values of the query spec.
            try {
                qs.setRoleDeprovisioningId(getProvisioningId());
            }
            catch (EnterpriseFieldException efe) {
                String errMsg = "An error occurred setting the values of the RoleDeprovisioning query spec. The exception is: " + efe.getMessage();
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
                List<RoleDeprovisioning> results = getRoleDeprovisioningProvider().query(qs);
                setRoleDeprovisioning(results.get(0));
            }
            catch (ProviderException pe) {
                String errMsg = "An error occurred querying for the  current state of a RoleDeprovisioning object. The exception is: " + pe.getMessage();
                logger.error(LOGTAG + errMsg);
            }
        }

        private RoleDeprovisioning getRoleDeprovisioning() { return roleDeprovisioning; }
        private void setRoleDeprovisioning(RoleDeprovisioning v) { roleDeprovisioning = v; }
        private String getProvisioningId() { return roleDeprovisioning.getRoleDeprovisioningId(); }

        private long getExecutionStartTime() { return executionStartTime; }
        private void setExecutionStartTime(long time) { executionStartTime = time; }
    }
}