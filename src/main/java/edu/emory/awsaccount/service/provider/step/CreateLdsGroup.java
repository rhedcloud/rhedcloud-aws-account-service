/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2017 Emory University. All rights reserved.
 ******************************************************************************/
package edu.emory.awsaccount.service.provider.step;

import java.util.List;
import java.util.Properties;

import javax.jms.JMSException;

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

import com.amazon.aws.moa.objects.resources.v1_0.Property;

import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;
import edu.emory.moa.jmsobjects.lightweightdirectoryservices.v1_0.Group;
import edu.emory.moa.objects.resources.v1_0.GroupQuerySpecification;


/**
 * If this is a new account, provision a group for the admin role
 * for the new account in the Lightweight Directory Service (LDS).
 * <P>
 *
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 26 December 2018
 **/
public class CreateLdsGroup extends AbstractStep implements Step {

    private ProducerPool m_ldsServiceProducerPool = null;
    private String m_groupDescriptionTemplate = null;
    private String m_groupDnTemplate = null;

    public void init (String provisioningId, Properties props,
            AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp)
            throws StepException {

        super.init(provisioningId, props, aConfig, vpcpp);

        String LOGTAG = getStepTag() + "[CreateLdsGroup.init] ";

        logger.info(LOGTAG + "Getting custom step properties...");
        String groupDescriptionTemplate = getProperties()
                .getProperty("groupDescriptionTemplate", null);
        setGroupDescriptionTemplate(groupDescriptionTemplate);
        logger.info(LOGTAG + "groupDescriptionTemplate is: " +
                getGroupDescriptionTemplate());

        String groupDnTemplate = getProperties().getProperty("groupDnTemplate", null);
        setGroupDnTemplate(groupDnTemplate);
        logger.info(LOGTAG + "groupDnTemplate is: " + getGroupDnTemplate());

        // This step needs to send messages to the LDS Service
        // to provision or deprovision the groups for the new account.
        ProducerPool p2p1 = null;
        try {
            p2p1 = (ProducerPool)getAppConfig()
                .getObject("LdsServiceProducerPool");
            setLdsServiceProducerPool(p2p1);
        }
        catch (EnterpriseConfigurationObjectException ecoe) {
            // An error occurred retrieving an object from AppConfig. Log it and
            // throw an exception.
            String errMsg = "An error occurred retrieving an object from " +
                    "AppConfig. The exception is: " + ecoe.getMessage();
            logger.error(LOGTAG + errMsg);
            addResultProperty("errorMessage", errMsg);
            throw new StepException(errMsg);
        }

        logger.info(LOGTAG + "Initialization complete.");
    }

    protected List<Property> run() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[CreateLdsGroup.run] ";
        logger.info(LOGTAG + "Begin running the step.");

        // Get the generateNewAccount and the newAccountId properties
        // from previous steps to determine if a new account has been
        // provisioned
        String allocateNewAccount = getStepPropertyValue("GENERATE_NEW_ACCOUNT", "allocateNewAccount");
        String newAccountId = getStepPropertyValue("GENERATE_NEW_ACCOUNT", "newAccountId");

        boolean allocatedNewAccount = Boolean.parseBoolean(allocateNewAccount);
        logger.info(LOGTAG + "allocatedNewAccount: " + allocatedNewAccount);
        logger.info(LOGTAG + "newAccountId: " + newAccountId);

        // If allocatedNewAccount is true and newAccountId is not null,
        // Send an Group.Create-Request to the AWS Account service.
        if (allocatedNewAccount && (newAccountId != null && !newAccountId.equals(PROPERTY_VALUE_NOT_AVAILABLE))) {
            logger.info(LOGTAG + "allocatedNewAccount is true and newAccountId " +
                "is not null. Sending an Group.Create-Request to create an LDS group.");

            // Get a configured Group object from AppConfig.
            Group group = new Group();
            GroupQuerySpecification querySpec = new GroupQuerySpecification();
            try {
                group = (Group)getAppConfig()
                    .getObjectByType(group.getClass().getName());
                querySpec = (GroupQuerySpecification)getAppConfig()
                    .getObjectByType(querySpec.getClass().getName());
            }
            catch (EnterpriseConfigurationObjectException ecoe) {
                String errMsg = "An error occurred retrieving an object from " +
                  "AppConfig. The exception is: " + ecoe.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg, ecoe);
            }

            // Set the values of the Group.
            try {
                group.addobjectClass("group");
                group.addobjectClass("top");
                group.setdescription(buildDescriptionValueFromTemplate(newAccountId));
                group.setdistinguishedName(buildDnValueFromTemplate(newAccountId));
            }
            catch (EnterpriseFieldException efe) {
                String errMsg = "An error occurred setting the values of the " +
                        "object. The exception is: " + efe.getMessage();
                  logger.error(LOGTAG + errMsg);
                  throw new StepException(errMsg, efe);
            }

            // Log the state of the Group.
            try {
                logger.info(LOGTAG + "Group is: " +
                    group.toXmlString());
            }
            catch (XmlEnterpriseObjectException xeoe) {
                String errMsg = "An error occurred serializing the object " +
                        "to XML. The exception is: " + xeoe.getMessage();
                  logger.error(LOGTAG + errMsg);
                  throw new StepException(errMsg, xeoe);
            }

            // Get a producer from the pool
            RequestService rs = null;
            try {
                rs = (RequestService)getLdsServiceProducerPool()
                    .getExclusiveProducer();
            }
            catch (JMSException jmse) {
                String errMsg = "An error occurred getting a producer " +
                    "from the pool. The exception is: " + jmse.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg, jmse);
            }

            try {
                long createStartTime = System.currentTimeMillis();
                group.create(rs);
                long createTime = System.currentTimeMillis() - createStartTime;
                logger.info(LOGTAG + "Created Group  in "
                    + createTime + " ms.");
                addResultProperty("createdGroup", "true");
                addResultProperty("distinguishedName", group.getdistinguishedName());

            }
            catch (EnterpriseObjectCreateException eoce) {
                String errMsg = "An error occurred creating the  " +
                  "Group object. " +
                  "The exception is: " + eoce.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg, eoce);
            }
            finally {
                // Release the producer back to the pool
                getLdsServiceProducerPool()
                    .releaseProducer((MessageProducer)rs);
            }

            // Query for the new group by dn to get the generated GUID.
            // Set the values of the query spec.
            try {
                querySpec.setdistinguishedName(group.getdistinguishedName());
            }
            catch (EnterpriseFieldException efe) {
                String errMsg = "An error occurred setting the values of the " +
                        "object. The exception is: " + efe.getMessage();
                  logger.error(LOGTAG + errMsg);
                  throw new StepException(errMsg, efe);
            }

            // Log the state of the query spec.
            try {
                logger.info(LOGTAG + "querySpec is: " +
                    group.toXmlString());
            }
            catch (XmlEnterpriseObjectException xeoe) {
                String errMsg = "An error occurred serializing the object " +
                        "to XML. The exception is: " + xeoe.getMessage();
                  logger.error(LOGTAG + errMsg);
                  throw new StepException(errMsg, xeoe);
            }

            // Get a producer from the pool
            rs = null;
            try {
                rs = (RequestService)getLdsServiceProducerPool()
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
                results = group.query(querySpec, rs);
                long queryTime = System.currentTimeMillis() - queryStartTime;
                logger.info(LOGTAG + "Queries for group in "
                    + queryTime + " ms. There are " + results.size() + " result(s).");
                if (results.size() == 1) {
                    Group resultGroup = (Group)results.get(0);
                    String guid = resultGroup.getobjectGUID();
                    logger.info(LOGTAG + "GUID for new group is: " + guid);
                    addResultProperty("guid", guid);

                }
                else {
                    String errMsg = "Invalid number of groups returned. " +
                        "Expected 1, got " + results.size();
                    logger.error(errMsg);
                    throw new StepException(errMsg);
                }

            }
            catch (EnterpriseObjectQueryException eoqe) {
                String errMsg = "An error occurred querying for the  " +
                  "Group object. The exception is: " + eoqe.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg, eoqe);
            }
            finally {
                // Release the producer back to the pool
                getLdsServiceProducerPool()
                    .releaseProducer((MessageProducer)rs);
            }

        }

        // Otherwise, no new account was provisioned and an new organization
        // unit is not necessary.
        else {
            logger.info(LOGTAG + "allocatedNewAccount is false or there " +
                "is no newAccountId. There is no need to create a new " +
                "groupt.");
            addResultProperty("allocatedNewAccount",
                Boolean.toString(allocatedNewAccount));
            addResultProperty("newAccountId", newAccountId);
            addResultProperty("createdGroup", "not applicable");

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
            "[CreateLdsGroup.simulate] ";
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
            "[CreateLdsGroup.fail] ";
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
        String LOGTAG = getStepTag() +
            "[CreateLdsGroup.rollback] ";

        // Get the generateNewAccount and the newAccountId properties
        // from previous steps to determine if a new account has been
        // provisioned
        String createdGroup =
            getResultProperty("createdGroup");
        boolean rollbackRequired =
            Boolean.parseBoolean(createdGroup);
        String distinguishedName =
            getResultProperty("distinguishedName");

        // If the step property createdGroup is true,
        // Query for the Group and then delete it.
        if (rollbackRequired) {
            // Query for the Group by distinguished name.

            // Get a configured Group object and query spec
            // from AppConfig.
            Group group = new Group();
            GroupQuerySpecification querySpec =
                new GroupQuerySpecification();
            try {
                group = (Group)getAppConfig()
                        .getObjectByType(group.getClass().getName());
                querySpec = (GroupQuerySpecification)getAppConfig()
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
                querySpec.setdistinguishedName(distinguishedName);
            }
            catch (EnterpriseFieldException efe) {
                String errMsg = "An error occurred setting the values of the " +
                        "object. The exception is: " + efe.getMessage();
                  logger.error(LOGTAG + errMsg);
                  throw new StepException(errMsg, efe);
            }

            // Log the state of the query spec.
            try {
                logger.info(LOGTAG + "query spec is: " +
                    querySpec.toXmlString());
            }
            catch (XmlEnterpriseObjectException xeoe) {
                String errMsg = "An error occurred serializing the object " +
                        "to XML. The exception is: " + xeoe.getMessage();
                  logger.error(LOGTAG + errMsg);
                  throw new StepException(errMsg, xeoe);
            }

            // Get a producer from the pool
            RequestService rs = null;
            try {
                rs = (RequestService)getLdsServiceProducerPool()
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
                results = group.query(querySpec, rs);
                long queryTime = System.currentTimeMillis() - queryStartTime;
                logger.info(LOGTAG + "Queried for Group in "
                    + queryTime + " ms. There are " + results.size() +
                    " result(s).");
            }
            catch (EnterpriseObjectQueryException eoqe) {
                String errMsg = "An error occurred querying for the  " +
                  "VpnConnectionProfile object. " +
                  "The exception is: " + eoqe.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg, eoqe);
            }
            finally {
                // Release the producer back to the pool
                getLdsServiceProducerPool()
                    .releaseProducer((MessageProducer)rs);
            }

            // If there is exactly one result delete it and set result
            // properties.
            if (results.size() == 1) {
                group = (Group)results.get(0);

                // Log the state of the Group.
                try {
                    logger.info(LOGTAG + "Group is: " +
                        group.toXmlString());
                }
                catch (XmlEnterpriseObjectException xeoe) {
                    String errMsg = "An error occurred serializing the object " +
                            "to XML. The exception is: " + xeoe.getMessage();
                      logger.error(LOGTAG + errMsg);
                      throw new StepException(errMsg, xeoe);
                }

                // Get a producer from the pool
                rs = null;
                try {
                    rs = (RequestService)getLdsServiceProducerPool()
                        .getExclusiveProducer();
                }
                catch (JMSException jmse) {
                    String errMsg = "An error occurred getting a producer " +
                        "from the pool. The exception is: " + jmse.getMessage();
                    logger.error(LOGTAG + errMsg);
                    throw new StepException(errMsg, jmse);
                }

                try {
                    long deleteStartTime = System.currentTimeMillis();
                    group.delete("Delete", rs);
                    long deleteTime = System.currentTimeMillis() - deleteStartTime;
                    logger.info(LOGTAG + "Deleted Group in "
                        + deleteTime + " ms.");
                    addResultProperty("deletedGroup", "true");
                }
                catch (EnterpriseObjectDeleteException eode) {
                    String errMsg = "An error occurred deleting the  " +
                      "Group object. The exception is: " + eode.getMessage();
                    logger.error(LOGTAG + errMsg);
                    throw new StepException(errMsg, eode);
                }
                finally {
                    // Release the producer back to the pool
                    getLdsServiceProducerPool()
                        .releaseProducer((MessageProducer)rs);
                }
            }

            // Otherwise, log an error.
            else {
                String errMsg = "Invalid number of Group objects"
                    + " returned. Got " + results.size() +
                    ", exected exactly 1.";
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg);
            }
        }

        // No group was created, there is nothing to roll back.
        else {
            logger.info("No group was created. There is nothing "
                + "to roll back.");
            addResultProperty("deletedGroup", "not applicable");
        }

        update(ROLLBACK_STATUS, SUCCESS_RESULT);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
    }

    private void setLdsServiceProducerPool(ProducerPool pool) {
        m_ldsServiceProducerPool = pool;
    }

    private ProducerPool getLdsServiceProducerPool() {
        return m_ldsServiceProducerPool;
    }

    private void setGroupDescriptionTemplate (String template) throws
        StepException {

        if (template == null) {
            String errMsg = "groupDescriptionTemplate property is null. " +
                "Can't continue.";
            throw new StepException(errMsg);
        }

        m_groupDescriptionTemplate = template;
    }

    private String getGroupDescriptionTemplate() {
        return m_groupDescriptionTemplate;
    }

    private String buildDescriptionValueFromTemplate(String accountId) {
        String description = getGroupDescriptionTemplate()
            .replace("ACCOUNT_NUMBER", accountId);
        return description;
    }

    private void setGroupDnTemplate (String template) throws
        StepException {

        if (template == null) {
            String errMsg = "groupDnTemplate property is null. " +
                "Can't continue.";
            throw new StepException(errMsg);
        }

        m_groupDnTemplate = template;
    }

    private String getGroupDnTemplate() {
        return m_groupDnTemplate;
    }

    private String buildDnValueFromTemplate(String accountId) {
        String dn = getGroupDnTemplate()
            .replace("ACCOUNT_NUMBER", accountId);
        return dn;
    }

}
