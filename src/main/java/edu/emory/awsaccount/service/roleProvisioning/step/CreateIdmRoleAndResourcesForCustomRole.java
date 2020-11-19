/* *****************************************************************************
 This file is part of the RHEDcloud AWS Account Service.

 Copyright 2020 RHEDcloud Foundation. All rights reserved.
 ******************************************************************************/

package edu.emory.awsaccount.service.roleProvisioning.step;

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.RoleProvisioningRequisition;
import edu.emory.awsaccount.service.provider.RoleProvisioningProvider;
import edu.emory.moa.jmsobjects.identity.v1_0.Entitlement;
import edu.emory.moa.jmsobjects.identity.v1_0.Resource;
import edu.emory.moa.jmsobjects.identity.v1_0.Role;
import edu.emory.moa.objects.resources.v1_0.RoleRequisition;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.PointToPointProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectGenerateException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.transport.RequestService;

import javax.jms.JMSException;
import java.util.List;
import java.util.Properties;

/**
 * Create an IDM role for the custom role.
 */
public class CreateIdmRoleAndResourcesForCustomRole extends AbstractStep implements Step {
    private ProducerPool m_idmServiceProducerPool = null;
    private int m_requestTimeoutIntervalInMillis = 10000;
    private String groupDnTemplate = null;

    public void init(String provisioningId, Properties props, AppConfig aConfig, RoleProvisioningProvider rpp) throws StepException {
        super.init(provisioningId, props, aConfig, rpp);

        String LOGTAG = getStepTag() + "[CreateIdmRoleAndResourcesForCustomRole.init] ";

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
        setGroupDnTemplate(getMandatoryStringProperty(LOGTAG, "groupDnTemplate", false));

        logger.info(LOGTAG + "Initialization complete.");
    }

    protected List<Property> run() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[CreateIdmRoleAndResourcesForAdminRole.run] ";
        logger.info(LOGTAG + "Begin running the step.");

        addResultProperty(STEP_EXECUTION_METHOD_PROPERTY_KEY, STEP_EXECUTION_METHOD_EXECUTED);

        // the account and custom role name was specified in the requisition
        RoleProvisioningRequisition roleProvisioningRequisition = getRoleProvisioning().getRoleProvisioningRequisition();
        String accountId = roleProvisioningRequisition.getAccountId();
        String roleName = roleProvisioningRequisition.getRoleName();

        // Get some properties from previous steps.
        String ldsGroupGuid = getStepPropertyValue("CREATE_LDS_GROUP_FOR_CUSTOM_ROLE", "guid");

        // Send a Role.Generate-Request to the IDM service.

        // Get a configured Role object and RoleRequisition from AppConfig.
        Role role;
        RoleRequisition req;
        try {
            role = (Role) getAppConfig().getObjectByType(Role.class.getName());
            req = (RoleRequisition) getAppConfig().getObjectByType(RoleRequisition.class.getName());
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        // Set the values of the requisition.
        try {
            // Main fields
            String roleNameTemplate = "RGR_AWS-ACCOUNT_NUMBER-CUSTOM_ROLE_NAME";
            req.setRoleName(buildValueFromTemplate(roleNameTemplate, accountId, roleName));
            req.setRoleDescription("Provisions members to various AWS resources");
            req.setRoleCategoryKey("aws");

            // Resource 1
            Resource res1 = role.newResource();
            String res1name = "MDSG_AWS-ACCOUNT_NUMBER-CUSTOM_ROLE_NAME";
            String res1desc = "Provisions members to group CUSTOM_ROLE_NAME on MS LDS University Connector";
            res1.setResourceName(buildValueFromTemplate(res1name, accountId, roleName));
            res1.setResourceDescription(buildValueFromTemplate(res1desc, accountId, roleName));
            res1.setResourceCategoryKey("group");
            Entitlement ent1 = res1.newEntitlement();
            ent1.setEntitlementDN(buildValueFromTemplate(getGroupDnTemplate(), accountId, roleName));
            ent1.setEntitlementGuid(ldsGroupGuid);
            ent1.setEntitlementApplication("UMD");
            res1.setEntitlement(ent1);
            req.addResource(res1);

            // Resource 2
            Resource res2 = role.newResource();
            String res2name = "HDSG_AWS-ACCOUNT_NUMBER-CUSTOM_ROLE_NAME";
            String res2desc = "Provisions members to group CUSTOM_ROLE_NAME on MS LDS Healthcare Connector";
            res2.setResourceName(buildValueFromTemplate(res2name, accountId, roleName));
            res2.setResourceDescription(buildValueFromTemplate(res2desc, accountId, roleName));
            res2.setResourceCategoryKey("group");
            Entitlement ent2 = res2.newEntitlement();
            ent2.setEntitlementDN(buildValueFromTemplate(getGroupDnTemplate(), accountId, roleName));
            ent2.setEntitlementGuid(ldsGroupGuid);
            ent2.setEntitlementApplication("HMD");
            res2.setEntitlement(ent2);
            req.addResource(res2);
        }
        catch (EnterpriseFieldException e) {
            String errMsg = "An error occurred setting the values of the RoleRequisition. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        // Log the state of the RoleRequisition.
        try {
            logger.info(LOGTAG + "Role req is: " + req.toXmlString());
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

        List<Role> results;
        try {
            long generateStartTime = System.currentTimeMillis();
            results = role.generate(req, rs);
            long generateTime = System.currentTimeMillis() - generateStartTime;
            logger.info(LOGTAG + "Generated Role in " + generateTime + " ms.");
        }
        catch (EnterpriseObjectGenerateException e) {
            String errMsg = "An error occurred generating the object. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        finally {
            getIdmServiceProducerPool().releaseProducer((MessageProducer) rs);
        }

        // there should only be one result but log everything we got
        for (Role r : results) {
            try {
                logger.info(LOGTAG + "Generated role: " + r.toXmlString());
            }
            catch (XmlEnterpriseObjectException e) {
                String errMsg = "An error occurred serializing the object to XML. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg, e);
            }
        }

        // set result properties
        addResultProperty("customRoleIdmName", req.getRoleName());

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
        String LOGTAG = getStepTag() + "[CreateIdmRoleAndResourcesForCustomRole.simulate] ";
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
        String LOGTAG = getStepTag() + "[CreateIdmRoleAndResourcesForCustomRole.fail] ";
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
        super.rollback();
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[CreateIdmRoleAndResourcesForCustomRole.rollback] ";
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
    private String getGroupDnTemplate() { return groupDnTemplate; }
    private void setGroupDnTemplate (String v) { groupDnTemplate = v; }

    private String buildValueFromTemplate(String template, String accountId, String roleName) {
        return template.replace("ACCOUNT_NUMBER", accountId).replace("CUSTOM_ROLE_NAME", roleName);
    }
}
