/* *****************************************************************************
 This file is part of the RHEDcloud AWS Account Service.

 Copyright 2020 RHEDcloud Foundation. All rights reserved.
 ******************************************************************************/

package edu.emory.awsaccount.service.roleProvisioning.step;

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.RoleProvisioningRequisition;
import edu.emory.awsaccount.service.provider.RoleProvisioningProvider;
import edu.emory.moa.jmsobjects.lightweightdirectoryservices.v1_0.Group;
import edu.emory.moa.jmsobjects.lightweightdirectoryservices.v1_0.OrganizationalUnit;
import edu.emory.moa.objects.resources.v1_0.GroupQuerySpecification;
import edu.emory.moa.objects.resources.v1_0.OrganizationalUnitQuerySpecification;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectCreateException;
import org.openeai.moa.EnterpriseObjectDeleteException;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.moa.XmlEnterpriseObjectException;
import org.openeai.transport.RequestService;

import javax.jms.JMSException;
import java.util.List;
import java.util.Properties;
import java.util.UUID;


/**
 * Provision a group for the custom role for the account in the Lightweight Directory Service (LDS).
 */
public class CreateLdsGroupForCustomRole extends AbstractStep implements Step {
    private ProducerPool ldsServiceProducerPool = null;
    private String groupDescriptionTemplate = null;
    private String groupDnTemplate = null;
    private String organizationalUnitDescriptionTemplate = null;
    private String organizationalUnitDnTemplate = null;

    public void init(String provisioningId, Properties props, AppConfig aConfig, RoleProvisioningProvider rpp) throws StepException {
        super.init(provisioningId, props, aConfig, rpp);

        String LOGTAG = getStepTag() + "[CreateLdsGroupForCustomRole.init] ";

        // This step needs to send messages to the LDS Service to provision the group for the account.
        try {
            ProducerPool p = (ProducerPool) getAppConfig().getObject("LdsServiceProducerPool");
            setLdsServiceProducerPool(p);
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + e.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        logger.info(LOGTAG + "Getting custom step properties...");

        setGroupDescriptionTemplate(getMandatoryStringProperty(LOGTAG, "groupDescriptionTemplate", false));
        setGroupDnTemplate(getMandatoryStringProperty(LOGTAG, "groupDnTemplate", false));
        setOrganizationalUnitDescriptionTemplate(getMandatoryStringProperty(LOGTAG, "organizationalUnitDescriptionTemplate", false));
        setOrganizationalUnitDnTemplate(getMandatoryStringProperty(LOGTAG, "organizationalUnitDnTemplate", false));

        logger.info(LOGTAG + "Initialization complete.");
    }

    protected List<Property> run() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[CreateLdsGroupForCustomRole.run] ";
        logger.info(LOGTAG + "Begin running the step.");

        addResultProperty(STEP_EXECUTION_METHOD_PROPERTY_KEY, STEP_EXECUTION_METHOD_EXECUTED);

        // the account and custom role name was specified in the requisition
        RoleProvisioningRequisition requisition = getRoleProvisioning().getRoleProvisioningRequisition();
        String accountId = requisition.getAccountId();
        String roleName = requisition.getRoleName();

        createCustomRolesOrganizationalUnit(accountId, LOGTAG);
        createCustomRolesGroup(accountId, roleName, LOGTAG);
        queryCustomRolesGroupGuid(accountId, roleName, LOGTAG);

        // Update the step.
        update(STEP_STATUS_COMPLETED, STEP_RESULT_SUCCESS);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step run completed in " + time + "ms.");

        // Return the properties.
        return getResultProperties();
    }

    private void createCustomRolesOrganizationalUnit(String accountId, String LOGTAG) throws StepException {
        OrganizationalUnit ou;
        OrganizationalUnitQuerySpecification ouQuerySpec;
        try {
            ou = (OrganizationalUnit) getAppConfig().getObjectByType(OrganizationalUnit.class.getName());
            ouQuerySpec = (OrganizationalUnitQuerySpecification) getAppConfig().getObjectByType(OrganizationalUnitQuerySpecification.class.getName());
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        try {
            ouQuerySpec.setdistinguishedName(fromTemplate(getOrganizationalUnitDnTemplate(), accountId, ""));
        }
        catch (EnterpriseFieldException e) {
            String errMsg = "An error occurred setting the values of the object. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        // Log the state of the OrganizationalUnitQuerySpecification.
        try {
            logger.info(LOGTAG + "OrganizationalUnitQuerySpecification is: " + ouQuerySpec.toXmlString());
        }
        catch (XmlEnterpriseObjectException e) {
            String errMsg = "An error occurred serializing the object to XML. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        // Get a producer from the pool
        RequestService rs;
        try {
            rs = (RequestService) getLdsServiceProducerPool().getExclusiveProducer();
        }
        catch (JMSException e) {
            String errMsg = "An error occurred getting a producer from the pool. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        List<OrganizationalUnit> results;
        try {
            long queryStartTime = System.currentTimeMillis();
            results = ou.query(ouQuerySpec, rs);
            long queryTime = System.currentTimeMillis() - queryStartTime;
            logger.info(LOGTAG + "Queried for OrganizationalUnit in " + queryTime + " ms. There are " + results.size() + " result(s).");
        }
        catch (EnterpriseObjectQueryException e) {
            String errMsg = "An error occurred querying for the OU object. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        finally {
            getLdsServiceProducerPool().releaseProducer((MessageProducer) rs);
        }

        if (results.size() == 0) {
            try {
                ou.addobjectClass("organizationalUnit");
                ou.addobjectClass("top");
                ou.setdescription(fromTemplate(getOrganizationalUnitDescriptionTemplate(), accountId, ""));
                ou.setdistinguishedName(fromTemplate(getOrganizationalUnitDnTemplate(), accountId, ""));
            }
            catch (EnterpriseFieldException e) {
                String errMsg = "An error occurred setting the values of the object. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg, e);
            }

            // Log the state of the OrganizationalUnit.
            try {
                logger.info(LOGTAG + "OrganizationalUnit to be created is: " + ou.toXmlString());
            }
            catch (XmlEnterpriseObjectException e) {
                String errMsg = "An error occurred serializing the object to XML. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg, e);
            }

            // Get a producer from the pool
            try {
                rs = (RequestService) getLdsServiceProducerPool().getExclusiveProducer();
            }
            catch (JMSException e) {
                String errMsg = "An error occurred getting a producer from the pool. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg, e);
            }

            try {
                long createStartTime = System.currentTimeMillis();
                ou.create(rs);
                long createTime = System.currentTimeMillis() - createStartTime;
                logger.info(LOGTAG + "Created OU in " + createTime + "ms.");

                addResultProperty("createdOrganizationalUnit", "true");
                addResultProperty("distinguishedName", ou.getdistinguishedName());
            }
            catch (EnterpriseObjectCreateException e) {
                String errMsg = "An error occurred creating the OU object. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg, e);
            }
            finally {
                getLdsServiceProducerPool().releaseProducer((MessageProducer) rs);
            }
        }
        else {
            addResultProperty("createdOrganizationalUnit", "false");
            addResultProperty("distinguishedName", results.get(0).getdistinguishedName());
        }
    }

    private void createCustomRolesGroup(String accountId, String roleName, String LOGTAG) throws StepException {
        // Get a configured Group object from AppConfig.
        Group group;
        try {
            group = (Group) getAppConfig().getObjectByType(Group.class.getName());
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        // Set the values of the Group.
        try {
            group.addobjectClass("group");
            group.addobjectClass("top");
            group.setdescription(fromTemplate(getGroupDescriptionTemplate(), accountId, roleName));
            group.setdistinguishedName(fromTemplate(getGroupDnTemplate(), accountId, roleName));
        }
        catch (EnterpriseFieldException e) {
            String errMsg = "An error occurred setting the values of the object. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        // Log the state of the Group.
        try {
            logger.info(LOGTAG + "Group is: " + group.toXmlString());
        }
        catch (XmlEnterpriseObjectException e) {
            String errMsg = "An error occurred serializing the object to XML. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        // Get a producer from the pool
        RequestService rs;
        try {
            rs = (RequestService) getLdsServiceProducerPool().getExclusiveProducer();
        }
        catch (JMSException e) {
            String errMsg = "An error occurred getting a producer from the pool. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        try {
            long createStartTime = System.currentTimeMillis();
            group.create(rs);
            long createTime = System.currentTimeMillis() - createStartTime;
            logger.info(LOGTAG + "Created Group in " + createTime + "ms.");

            // result property tells rollback() there is work to do
            addResultProperty("createdGroup", "true");
            addResultProperty("distinguishedName", group.getdistinguishedName());
        }
        catch (EnterpriseObjectCreateException e) {
            String errMsg = "An error occurred creating the Group object. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        finally {
            getLdsServiceProducerPool().releaseProducer((MessageProducer) rs);
        }
    }

    private void queryCustomRolesGroupGuid(String accountId, String roleName, String LOGTAG) throws StepException {
        // Get a configured Group object from AppConfig.
        Group group;
        GroupQuerySpecification groupQuerySpec;
        try {
            group = (Group) getAppConfig().getObjectByType(Group.class.getName());
            groupQuerySpec = (GroupQuerySpecification) getAppConfig().getObjectByType(GroupQuerySpecification.class.getName());
        }
        catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        // Query for the new group by DN to get the generated GUID.
        // Set the values of the query spec.
        try {
            groupQuerySpec.setdistinguishedName(fromTemplate(getGroupDnTemplate(), accountId, roleName));
        }
        catch (EnterpriseFieldException e) {
            String errMsg = "An error occurred setting the values of the object. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        // Log the state of the query spec.
        try {
            logger.info(LOGTAG + "Group query spec is: " + groupQuerySpec.toXmlString());
        }
        catch (XmlEnterpriseObjectException e) {
            String errMsg = "An error occurred serializing the object to XML. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        // Get a producer from the pool
        RequestService rs;
        try {
            rs = (RequestService) getLdsServiceProducerPool().getExclusiveProducer();
        }
        catch (JMSException e) {
            String errMsg = "An error occurred getting a producer from the pool. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }

        try {
            long queryStartTime = System.currentTimeMillis();
            @SuppressWarnings("unchecked")
            List<Group> results = group.query(groupQuerySpec, rs);
            long queryTime = System.currentTimeMillis() - queryStartTime;
            logger.info(LOGTAG + "Queries for group in " + queryTime + "ms. There are " + results.size() + " result(s).");
            if (results.size() == 1) {
                String guid = results.get(0).getobjectGUID();
                addResultProperty("guid", guid);
                logger.info(LOGTAG + "GUID for new group is: " + guid);
            }
            else {
                String errMsg = "Invalid number of groups returned. Expected 1, got " + results.size();
                logger.error(errMsg);
                throw new StepException(errMsg);
            }
        }
        catch (EnterpriseObjectQueryException e) {
            String errMsg = "An error occurred querying for the Group object. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        finally {
            getLdsServiceProducerPool().releaseProducer((MessageProducer) rs);
        }
    }

    protected List<Property> simulate() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[CreateLdsGroupForCustomRole.simulate] ";
        logger.info(LOGTAG + "Begin step simulation.");

        addResultProperty(STEP_EXECUTION_METHOD_PROPERTY_KEY, STEP_EXECUTION_METHOD_SIMULATED);

        // simulated result properties
        addResultProperty("createdGroup", "false");
        addResultProperty("distinguishedName", fromTemplate(getGroupDnTemplate(), "123456789012", "SimulatedRoleName"));
        addResultProperty("guid", UUID.randomUUID().toString());

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
        String LOGTAG = getStepTag() + "[CreateLdsGroupForCustomRole.fail] ";
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
        String LOGTAG = getStepTag() + "[CreateLdsGroupForCustomRole.rollback] ";

        String createdGroup = getResultProperty("createdGroup");
        boolean rollbackRequired = Boolean.parseBoolean(createdGroup);
        String distinguishedName = getResultProperty("distinguishedName");

        // If the step property createdGroup is true,
        // Query for the Group and then delete it.
        if (rollbackRequired) {
            // Query for the Group by distinguished name.

            // Get a configured Group object and query spec from AppConfig.
            Group group;
            GroupQuerySpecification querySpec;
            try {
                group = (Group) getAppConfig().getObjectByType(Group.class.getName());
                querySpec = (GroupQuerySpecification) getAppConfig().getObjectByType(GroupQuerySpecification.class.getName());
            }
            catch (EnterpriseConfigurationObjectException e) {
                String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg, e);
            }

            // Set the values of the querySpec.
            try {
                querySpec.setdistinguishedName(distinguishedName);
            }
            catch (EnterpriseFieldException e) {
                String errMsg = "An error occurred setting the values of the object. The exception is: " + e.getMessage();
                  logger.error(LOGTAG + errMsg);
                  throw new StepException(errMsg, e);
            }

            // Log the state of the query spec.
            try {
                logger.info(LOGTAG + "query spec is: " + querySpec.toXmlString());
            }
            catch (XmlEnterpriseObjectException e) {
                String errMsg = "An error occurred serializing the object to XML. The exception is: " + e.getMessage();
                  logger.error(LOGTAG + errMsg);
                  throw new StepException(errMsg, e);
            }

            // Get a producer from the pool
            RequestService rs;
            try {
                rs = (RequestService) getLdsServiceProducerPool().getExclusiveProducer();
            }
            catch (JMSException e) {
                String errMsg = "An error occurred getting a producer from the pool. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg, e);
            }

            List<Group> results;
            try {
                long queryStartTime = System.currentTimeMillis();
                results = group.query(querySpec, rs);
                long queryTime = System.currentTimeMillis() - queryStartTime;
                logger.info(LOGTAG + "Queried for Group in " + queryTime + "ms. There are " + results.size() + " result(s).");
            }
            catch (EnterpriseObjectQueryException e) {
                String errMsg = "An error occurred querying for the Group object. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg, e);
            }
            finally {
                getLdsServiceProducerPool().releaseProducer((MessageProducer) rs);
            }

            // If there is exactly one result delete it and set result properties.
            if (results.size() == 1) {
                group = results.get(0);

                // Log the state of the Group.
                try {
                    logger.info(LOGTAG + "Group is: " + group.toXmlString());
                }
                catch (XmlEnterpriseObjectException e) {
                    String errMsg = "An error occurred serializing the object to XML. The exception is: " + e.getMessage();
                      logger.error(LOGTAG + errMsg);
                      throw new StepException(errMsg, e);
                }

                // Get a producer from the pool
                try {
                    rs = (RequestService) getLdsServiceProducerPool().getExclusiveProducer();
                }
                catch (JMSException e) {
                    String errMsg = "An error occurred getting a producer from the pool. The exception is: " + e.getMessage();
                    logger.error(LOGTAG + errMsg);
                    throw new StepException(errMsg, e);
                }

                try {
                    long deleteStartTime = System.currentTimeMillis();
                    group.delete("Delete", rs);
                    long deleteTime = System.currentTimeMillis() - deleteStartTime;
                    logger.info(LOGTAG + "Deleted Group in " + deleteTime + "ms.");
                    addResultProperty("deletedGroup", "true");
                }
                catch (EnterpriseObjectDeleteException e) {
                    String errMsg = "An error occurred deleting the Group object. The exception is: " + e.getMessage();
                    logger.error(LOGTAG + errMsg);
                    throw new StepException(errMsg, e);
                }
                finally {
                    getLdsServiceProducerPool().releaseProducer((MessageProducer) rs);
                }
            }
            else {
                String errMsg = "Invalid number of Group objects returned. Got " + results.size() + ", expected exactly 1.";
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg);
            }
        }
        else {
            logger.info("No group was created. There is nothing to roll back.");
            addResultProperty("deletedGroup", "not applicable");
        }

        // Update the step.
        update(STEP_STATUS_ROLLBACK, STEP_RESULT_SUCCESS);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
    }

    private ProducerPool getLdsServiceProducerPool() { return ldsServiceProducerPool; }
    private void setLdsServiceProducerPool(ProducerPool v) { ldsServiceProducerPool = v; }
    private String getGroupDescriptionTemplate() { return groupDescriptionTemplate; }
    private void setGroupDescriptionTemplate (String v) { groupDescriptionTemplate = v; }
    private String getGroupDnTemplate() { return groupDnTemplate; }
    private void setGroupDnTemplate (String v) { groupDnTemplate = v; }
    public String getOrganizationalUnitDescriptionTemplate() { return organizationalUnitDescriptionTemplate; }
    public void setOrganizationalUnitDescriptionTemplate(String v) { organizationalUnitDescriptionTemplate = v; }
    public String getOrganizationalUnitDnTemplate() { return organizationalUnitDnTemplate; }
    public void setOrganizationalUnitDnTemplate(String v) { organizationalUnitDnTemplate = v; }

    private String fromTemplate(String template, String accountId, String roleName) {
        return template.replace("ACCOUNT_NUMBER", accountId).replace("CUSTOM_ROLE_NAME", roleName);
    }
}
