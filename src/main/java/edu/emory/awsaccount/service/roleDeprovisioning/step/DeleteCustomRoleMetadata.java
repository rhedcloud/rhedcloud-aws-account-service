/* *****************************************************************************
 This file is part of the RHEDcloud AWS Account Service.

 Copyright 2020 RHEDcloud Foundation. All rights reserved.
 ******************************************************************************/

package edu.emory.awsaccount.service.roleDeprovisioning.step;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.CustomRole;
import com.amazon.aws.moa.objects.resources.v1_0.CustomRoleQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.RoleDeprovisioningRequisition;
import edu.emory.awsaccount.service.provider.RoleDeprovisioningProvider;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectDeleteException;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.transport.RequestService;

import javax.jms.JMSException;
import java.util.List;
import java.util.Properties;


/**
 * Delete the CustomRole metadata.
 */
public class DeleteCustomRoleMetadata extends AbstractStep implements Step {
    private ProducerPool awsAccountServiceProducerPool = null;

    public void init(String provisioningId, Properties props, AppConfig aConfig, RoleDeprovisioningProvider rpp) throws StepException {
        super.init(provisioningId, props, aConfig, rpp);

        String LOGTAG = getStepTag() + "[DeleteCustomRoleMetadata.init] ";

        // This step needs to send messages to the AWS account service to delete CustomRole metadata.
        try {
            ProducerPool p = (ProducerPool) getAppConfig().getObject("AwsAccountServiceProducerPool");
            setAwsAccountServiceProducerPool(p);
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + e.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        logger.info(LOGTAG + "Initialization complete.");
    }

    protected List<Property> run() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[DeleteCustomRoleMetadata.run] ";
        logger.info(LOGTAG + "Begin running the step.");

        addResultProperty(STEP_EXECUTION_METHOD_PROPERTY_KEY, STEP_EXECUTION_METHOD_EXECUTED);

        // the account and custom role name was specified in the requisition
        RoleDeprovisioningRequisition requisition = getRoleDeprovisioning().getRoleDeprovisioningRequisition();
        String accountId = requisition.getAccountId();
        String roleName = requisition.getRoleName();

        // Get a configured objects from AppConfig.
        CustomRole customRole;
        CustomRoleQuerySpecification querySpec;
        try {
            customRole = (CustomRole) getAppConfig().getObjectByType(CustomRole.class.getName());
            querySpec = (CustomRoleQuerySpecification) getAppConfig().getObjectByType(CustomRoleQuerySpecification.class.getName());
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred getting CustomRole properties from AppConfig. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        try {
            querySpec.setAccountId(accountId);

            logger.info(LOGTAG + "CustomRoleQuerySpecification is: " + querySpec.toXmlString());
        }
        catch (EnterpriseFieldException e) {
            String errMsg = "An error occurred setting field values of the CustomRoleQuerySpecification object. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        catch (XmlEnterpriseObjectException e) {
            String errMsg = "An error occurred serializing the CustomRoleQuerySpecification to XML. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        // Get a producer from the pool
        RequestService rs;
        try {
            rs = (RequestService) getAwsAccountServiceProducerPool().getExclusiveProducer();
        }
        catch (JMSException e) {
            String errMsg = "An error occurred getting a producer from the pool. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        try {
            long elapsedStartTime = System.currentTimeMillis();
            @SuppressWarnings("unchecked")
            List<CustomRole> results = customRole.query(querySpec, rs);
            long elapsedTime = System.currentTimeMillis() - elapsedStartTime;
            logger.info(LOGTAG + "Queried CustomRole in " + elapsedTime + " ms.");

            int index = 1;
            for (CustomRole cr : results) {
                if (cr.getAccountId().equals(accountId) && cr.getRoleName().equals(roleName)) {
                    try {
                        logger.info(LOGTAG + "CustomRole to be deleted is: " + cr.toXmlString());
                    }
                    catch (XmlEnterpriseObjectException e) {
                        String errMsg = "An error occurred serializing the CustomRole to be deleted to XML. The exception is: " + e.getMessage();
                        logger.error(LOGTAG + errMsg);
                        throw new StepException(errMsg, e);
                    }

                    String customRoleId = cr.getCustomRoleId();

                    elapsedStartTime = System.currentTimeMillis();
                    cr.delete("Delete", rs);
                    elapsedTime = System.currentTimeMillis() - elapsedStartTime;
                    logger.info(LOGTAG + "Deleted CustomRole in " + elapsedTime + " ms.");

                    addResultProperty("deletedCustomRoleId" + index++, customRoleId);
                }
            }

            if (index == 1) {
                addResultProperty("deletedCustomRoleId", "not applicable");
            }
        }
        catch (EnterpriseObjectQueryException e) {
            String errMsg = "An error occurred querying the CustomRole. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        catch (EnterpriseObjectDeleteException e) {
            String errMsg = "An error occurred deleting the CustomRole. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        finally {
            getAwsAccountServiceProducerPool().releaseProducer((MessageProducer)rs);
        }

        // Update the step.
        update(STEP_STATUS_COMPLETED, STEP_RESULT_SUCCESS);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step run completed in " + time + "ms.");

        // Return the properties.
        return getResultProperties();
    }

    protected List<Property> simulate() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[DeleteCustomRoleMetadata.simulate] ";
        logger.info(LOGTAG + "Begin step simulation.");

        addResultProperty(STEP_EXECUTION_METHOD_PROPERTY_KEY, STEP_EXECUTION_METHOD_SIMULATED);

        // Update the step.
        update(STEP_STATUS_COMPLETED, STEP_RESULT_SUCCESS);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step simulation completed in " + time + "ms.");

        // Return the properties.
        return getResultProperties();
    }

    protected List<Property> fail() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[DeleteCustomRoleMetadata.fail] ";
        logger.info(LOGTAG + "Begin step failure simulation.");

        addResultProperty(STEP_EXECUTION_METHOD_PROPERTY_KEY, STEP_EXECUTION_METHOD_FAILURE);

        // Update the step.
        update(STEP_STATUS_COMPLETED, STEP_RESULT_FAILURE);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step failure simulation completed in " + time + "ms.");

        // Return the properties.
        return getResultProperties();
    }

    public void rollback() throws StepException {
        long startTime = System.currentTimeMillis();
        super.rollback();
        String LOGTAG = getStepTag() + "[DeleteCustomRoleMetadata.rollback] ";
        logger.info(LOGTAG + "Rollback called, but this step has nothing to roll back.");

        // Update the step.
        update(STEP_STATUS_ROLLBACK, STEP_RESULT_SUCCESS);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
    }

    public ProducerPool getAwsAccountServiceProducerPool() { return awsAccountServiceProducerPool; }
    public void setAwsAccountServiceProducerPool(ProducerPool v) { this.awsAccountServiceProducerPool = v; }
}
