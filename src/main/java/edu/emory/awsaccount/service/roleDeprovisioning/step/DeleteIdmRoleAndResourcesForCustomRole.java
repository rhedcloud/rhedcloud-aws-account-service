/* *****************************************************************************
 This file is part of the RHEDcloud AWS Account Service.

 Copyright 2020 RHEDcloud Foundation. All rights reserved.
 ******************************************************************************/

package edu.emory.awsaccount.service.roleDeprovisioning.step;

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.RoleDeprovisioningRequisition;
import edu.emory.awsaccount.service.provider.RoleDeprovisioningProvider;
import edu.emory.moa.jmsobjects.identity.v1_0.Role;
import edu.emory.moa.objects.resources.v1_0.RoleQuerySpecification;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.PointToPointProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectDeleteException;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.transport.RequestService;

import javax.jms.JMSException;
import java.util.List;
import java.util.Properties;

/**
 * Delete the IDM role for the custom role.
 */
public class DeleteIdmRoleAndResourcesForCustomRole extends AbstractStep implements Step {
    private ProducerPool m_idmServiceProducerPool;
    private int m_requestTimeoutIntervalInMillis;
    private String customRoleDnTemplate;

    public void init(String provisioningId, Properties props, AppConfig aConfig, RoleDeprovisioningProvider rpp) throws StepException {
        super.init(provisioningId, props, aConfig, rpp);

        String LOGTAG = getStepTag() + "[DeleteIdmRoleAndResourcesForCustomRole.init] ";

        // This step needs to send messages to the IDM service to create account roles.
        try {
            ProducerPool p = (ProducerPool) getAppConfig().getObject("IdmServiceProducerPool");
            setIdmServiceProducerPool(p);
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + e.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        logger.info(LOGTAG + "Getting custom step properties...");

        setRequestTimeoutIntervalInMillis(getWithDefaultIntegerProperty(LOGTAG, "requestTimeoutIntervalInMillis", "10000", false));
        setCustomRoleDnTemplate(getMandatoryStringProperty(LOGTAG, "customRoleDnTemplate", false));

        logger.info(LOGTAG + "Initialization complete.");
    }

    protected List<Property> run() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[DeleteIdmRoleAndResourcesForCustomRole.run] ";
        logger.info(LOGTAG + "Begin running the step.");

        addResultProperty(STEP_EXECUTION_METHOD_PROPERTY_KEY, STEP_EXECUTION_METHOD_EXECUTED);

        // the account and custom role name was specified in the requisition
        RoleDeprovisioningRequisition requisition = getRoleDeprovisioning().getRoleDeprovisioningRequisition();
        String accountId = requisition.getAccountId();
        String roleName = requisition.getRoleName();

        // Get configured objects from AppConfig.
        Role role;
        RoleQuerySpecification querySpec;
        try {
            role = (Role) getAppConfig().getObjectByType(Role.class.getName());
            querySpec = (RoleQuerySpecification) getAppConfig().getObjectByType(RoleQuerySpecification.class.getName());
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        try {
            querySpec.setRoleDN(buildValueFromTemplate(getCustomRoleDnTemplate(), accountId, roleName));

            logger.info(LOGTAG + "RoleQuerySpecification is: " + querySpec.toXmlString());
        }
        catch (EnterpriseFieldException e) {
            String errMsg = "An error occurred setting the values of the RoleRequisition. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        catch (XmlEnterpriseObjectException e) {
            String errMsg = "An error occurred serializing the object to XML. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        // Get a producer from the pool
        RequestService rs;
        try {
            PointToPointProducer p2p = (PointToPointProducer) getIdmServiceProducerPool().getExclusiveProducer();
            p2p.setRequestTimeoutInterval(getRequestTimeoutIntervalInMillis());
            rs = p2p;
        }
        catch (JMSException e) {
            String errMsg = "An error occurred getting a producer from the pool. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        try {
            long elapsedStartTime = System.currentTimeMillis();
            @SuppressWarnings("unchecked")
            List<Role> results = role.query(querySpec, rs);
            long elapsedTime = System.currentTimeMillis() - elapsedStartTime;
            logger.info(LOGTAG + "Queried Role in " + elapsedTime + " ms.");

            int index = 1;
            for (Role r : results) {
                try {
                    logger.info(LOGTAG + "Role to delete: " + r.toXmlString());
                }
                catch (XmlEnterpriseObjectException e) {
                    String errMsg = "An error occurred serializing the object to XML. The exception is: " + e.getMessage();
                    logger.error(LOGTAG + errMsg);
                    throw new StepException(errMsg, e);
                }

                String roleDn = r.getRoleDN();

                elapsedStartTime = System.currentTimeMillis();
                r.delete("Delete", rs);
                elapsedTime = System.currentTimeMillis() - elapsedStartTime;
                logger.info(LOGTAG + "Deleted Role in " + elapsedTime + " ms.");

                addResultProperty("deletedCustomRoleIdmDn" + index++, roleDn);
            }

            if (index == 1) {
                addResultProperty("deletedCustomRoleIdmDn", "not applicable");
            }
        }
        catch (EnterpriseObjectQueryException e) {
            String errMsg = "An error occurred querying the object. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        catch (EnterpriseObjectDeleteException e) {
            String errMsg = "An error occurred deleting the object. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        finally {
            getIdmServiceProducerPool().releaseProducer((MessageProducer) rs);
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
        String LOGTAG = getStepTag() + "[DeleteIdmRoleAndResourcesForCustomRole.simulate] ";
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
        String LOGTAG = getStepTag() + "[DeleteIdmRoleAndResourcesForCustomRole.fail] ";
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
        String LOGTAG = getStepTag() + "[DeleteIdmRoleAndResourcesForCustomRole.rollback] ";
        logger.info(LOGTAG + "Rollback called, but this step has nothing to roll back.");

        // Update the step.
        update(STEP_STATUS_ROLLBACK, STEP_RESULT_SUCCESS);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
    }

    private ProducerPool getIdmServiceProducerPool() { return m_idmServiceProducerPool; }
    private void setIdmServiceProducerPool(ProducerPool v) { m_idmServiceProducerPool = v; }
    private int getRequestTimeoutIntervalInMillis() { return m_requestTimeoutIntervalInMillis; }
    private void setRequestTimeoutIntervalInMillis(int v) { m_requestTimeoutIntervalInMillis = v; }
    private String getCustomRoleDnTemplate() { return customRoleDnTemplate; }
    private void setCustomRoleDnTemplate(String v) { customRoleDnTemplate = v; }

    private String buildValueFromTemplate(String template, String accountId, String roleName) {
        return template.replace("ACCOUNT_NUMBER", accountId).replace("CUSTOM_ROLE_NAME", roleName);
    }
}
