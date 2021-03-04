/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2017 Emory University. All rights reserved.
 ******************************************************************************/
package edu.emory.awsaccount.service.deprovisioning.step;

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import edu.emory.awsaccount.service.provider.AccountDeprovisioningProvider;
import edu.emory.moa.jmsobjects.lightweightdirectoryservices.v1_0.OrganizationalUnit;
import edu.emory.moa.objects.resources.v1_0.OrganizationalUnitQuerySpecification;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectDeleteException;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.transport.RequestService;

import javax.jms.JMSException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;


/**
 * Delete the groups that were created in the provisioning.
 * <P>
 *
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 21 May 2017
 * @author Tom Cervenka (tcerven@emory.edu)
 * @version 1.0 - 29 May 2020
 *
 **/
public class DeleteLdsOrganizationalUnit extends AbstractStep implements Step {
    private final String LOGTAG="DeleteLdsOrganizationalUnit says ";

    private ProducerPool m_ldsServiceProducerPool;
    private String m_organizationalUnitDnTemplate;
    private AppConfig m_aConfig;

    private final String OU = "OrganizationalUnit.v1_0";
    private final String OU_QUERY_SPEC = "OrganizationalUnitQuerySpecification.v1_0";



    public void init (String provisioningId, Properties props,
            AppConfig aConfig, AccountDeprovisioningProvider vpcpp) throws StepException {

        super.init(provisioningId, props, aConfig, vpcpp);

        String LOGTAG = getStepTag() + "[DeleteLdsOrganizationalUnit.init] ";

        m_aConfig = aConfig;

        logger.info(LOGTAG + "Getting custom step properties...");
        String organizationalUnitDnTemplate = getProperties()
                .getProperty("organizationalUnitDnTemplate", null);
        setOrganizationalUnitDnTemplate(organizationalUnitDnTemplate);
        logger.info(LOGTAG + "organizationalUnitDnTemplate is: " +
                getOrganizationalUnitDnTemplate());

        // Check that required objects are in the appConfig
        try {
            m_aConfig.getObject(OU);
            m_aConfig.getObject(OU_QUERY_SPEC);
        } catch (EnterpriseConfigurationObjectException ecoe) {
            String errMsg = "An error occurred retrieving the one of the objects from " +
                    "AppConfig. The exception is: " + ecoe.getMessage();
            logger.error(LOGTAG + errMsg);
            addResultProperty("errorMessage", errMsg);
            throw new StepException(errMsg);
        }


        // This step needs to send messages to the LDS Service
        // to provision or deprovision the OU for the new account.
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

    @SuppressWarnings("unchecked")
    private OrganizationalUnit queryForOu(String distinguishedName)
            throws StepException {
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

        List<OrganizationalUnit> results = null;
        try {
            long queryStartTime = System.currentTimeMillis();
            OrganizationalUnit ou = (OrganizationalUnit) m_aConfig.getObject(OU);
            OrganizationalUnitQuerySpecification querySpec =
                    (OrganizationalUnitQuerySpecification) m_aConfig.getObject(OU_QUERY_SPEC);
            querySpec.setdistinguishedName(distinguishedName);
            results = ou.query(querySpec, rs);
            long queryTime = System.currentTimeMillis() - queryStartTime;
            logger.info(LOGTAG + "Queried for OrganizationUnit in "
                    + queryTime + " ms. There are " + results.size() +
                    " result(s).");
        }
        catch (EnterpriseObjectQueryException eoqe) {
            String errMsg = "An error occurred querying for the  " +
                    "OrganizationalUnit object. " +
                    "The exception is: " + eoqe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, eoqe);
        } catch (EnterpriseConfigurationObjectException e) {
            String errMsg = "An error occurred retrieving the one of the objects from " +
                    "AppConfig. The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            addResultProperty("errorMessage", errMsg);
            throw new StepException(errMsg);
        } catch (EnterpriseFieldException e) {
            String errMsg = "An error occurred seting the DN in the  " +
                    "OrganizationalUnitQuerySpec object. " +
                    "The exception is: " + e.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, e);
        }
        finally {
            // Release the producer back to the pool
            getLdsServiceProducerPool()
            .releaseProducer((MessageProducer)rs);
        }
        if (results.size() == 1) {
            return results.get(0);
        } else {
            if (results.size()==0) {
                return null;
            } else {
                String errMsg = "Expected 1 OU from query but got"
                        + results.size() ;
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg);
            }

        }

    }

    private void deleteOu(OrganizationalUnit ou) throws StepException {
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
            long deleteStartTime = System.currentTimeMillis();
            ou.delete("Delete", rs);
            long deleteTime = System.currentTimeMillis() - deleteStartTime;
            logger.info(LOGTAG + "Deleted OU in "
                    + deleteTime + " ms.");
        }
        catch (EnterpriseObjectDeleteException eode) {
            String errMsg = "An error occurred deleting the  " +
                    "OrganizationalUnit object. The exception is: " + eode.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, eode);
        }
        finally {
            // Release the producer back to the pool
            getLdsServiceProducerPool()
            .releaseProducer((MessageProducer)rs);
        }
    }

    protected List<Property> run() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[DeleteLdsOrganizationalUnit.run] ";
        logger.info(LOGTAG + "Begin deleting OU.");

        String accountId = getAccountDeprovisioning().getAccountDeprovisioningRequisition().getAccountId();
        String distinguishedName = buildDnValueFromTemplate(accountId);
        logger.info(LOGTAG + "distinguishedName is "+distinguishedName);
        addResultProperty("distinguishedName", distinguishedName);

        // Query for the ou
        OrganizationalUnit ou = queryForOu(distinguishedName);
        if (ou != null) {
            deleteOu(ou);
            addResultProperty("deletedOu", "true");
            logger.info(LOGTAG + "Done deleting OU.");
        } else {
            //TODO do we have to set deletedOu=true here, too?
            //TODO should this be warn instead of info?
            logger.info(LOGTAG + "OU doesn't exist");
            addResultProperty("deletedOu", "false");
            addResultProperty("OuExists", "false");
        }

        // Set return properties.
        addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);

        // Update the step.
        update(COMPLETED_STATUS, SUCCESS_RESULT);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step run completed in " + time + "ms.");

        // Return the properties.
        return getResultProperties();

    }

    private ProducerPool getLdsServiceProducerPool() {
        return m_ldsServiceProducerPool;
    }

    protected List<Property> simulate() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() +
                "[DeleteLdsOrganizationalUnit.simulate] ";
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
                "[DeleteLdsOrganizationalUnit.fail] ";
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
        String LOGTAG = getStepTag() + "[DeleteLdsOrganizationalUnit.rollback] ";
        logger.info(LOGTAG + "Rollback called, but this step has nothing to roll back.");
        update(ROLLBACK_STATUS, SUCCESS_RESULT);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
    }

    private void setOrganizationalUnitDnTemplate (String template) throws
    StepException {

        if (template == null) {
            String errMsg = "organizationalUnitDnTemplate property is null. " +
                    "Can't continue.";
            throw new StepException(errMsg);
        }

        m_organizationalUnitDnTemplate = template;
    }
    private String getOrganizationalUnitDnTemplate() {
        return m_organizationalUnitDnTemplate;
    }

    private void setLdsServiceProducerPool(ProducerPool pool) {
        m_ldsServiceProducerPool = pool;
    }

    private String buildDnValueFromTemplate(String accountId) {
        String dn = getOrganizationalUnitDnTemplate()
                .replace("ACCOUNT_NUMBER", accountId);
        return dn;
    }

}
