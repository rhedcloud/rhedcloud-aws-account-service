/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the RHEDcloud AWS Account Service.

 Copyright (C) 2020 RHEDcloud Foundation. All rights reserved.
 ******************************************************************************/
package edu.emory.awsaccount.service.deprovisioning.step;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.VirtualPrivateCloud;
import com.amazon.aws.moa.objects.resources.v1_0.AccountDeprovisioningRequisition;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudQuerySpecification;
import edu.emory.awsaccount.service.provider.AccountDeprovisioningProvider;
import org.openeai.config.AppConfig;
import org.openeai.config.EnterpriseConfigurationObjectException;
import org.openeai.config.EnterpriseFieldException;
import org.openeai.jms.producer.MessageProducer;
import org.openeai.jms.producer.ProducerPool;
import org.openeai.moa.EnterpriseObjectQueryException;
import org.openeai.transport.RequestService;

import javax.jms.JMSException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * List VPCs associated with this account
 * <p>
 *
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 22 May 2020
 **/
public class ListVpcIds extends AbstractStep implements Step {

    private ProducerPool m_awsAccountServiceProducerPool = null;

    public void init(String provisioningId, Properties props,
                     AppConfig aConfig, AccountDeprovisioningProvider adp)
            throws StepException {

        super.init(provisioningId, props, aConfig, adp);

        String LOGTAG = getStepTag() + "[ListVpcIds.init] ";

        try {
            ProducerPool p = (ProducerPool) getAppConfig().getObject("AwsAccountServiceProducerPool");
            setAwsAccountServiceProducerPool(p);
        } catch (EnterpriseConfigurationObjectException ecoe) {
            String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + ecoe.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }

        logger.info(LOGTAG + "Initialization complete.");
    }

    protected List<Property> run() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[ListVpcIds.run] ";
        logger.info(LOGTAG + "Begin running the step.");

        // Get the AccountDeprovisioningRequisition
        AccountDeprovisioningRequisition req = getAccountDeprovisioning().getAccountDeprovisioningRequisition();

        // Get the accountId
        String accountId = req.getAccountId();
        logger.info(LOGTAG + "accountId is: " + accountId);
        addResultProperty("accountId", accountId);

        // Get a configured VPC object and query spec from AppConfig.
        VirtualPrivateCloud vpc = new VirtualPrivateCloud();
        VirtualPrivateCloudQuerySpecification querySpec = new VirtualPrivateCloudQuerySpecification();
        try {
            vpc = (VirtualPrivateCloud) getAppConfig().getObjectByType(vpc.getClass().getName());
            querySpec = (VirtualPrivateCloudQuerySpecification) getAppConfig().getObjectByType(querySpec.getClass().getName());

        } catch (EnterpriseConfigurationObjectException ecoe) {
            String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + ecoe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, ecoe);
        }

        // Set the values of the query spec.
        try {
            querySpec.setAccountId(accountId);
        } catch (EnterpriseFieldException efe) {
            String errMsg = "An error occurred setting the values of the query spec. The exception is: " + efe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, efe);
        }

        // Get a producer from the pool
        RequestService rs;
        try {
            rs = (RequestService) getAwsAccountServiceProducerPool().getExclusiveProducer();
        } catch (JMSException jmse) {
            String errMsg = "An error occurred getting a producer from the pool. The exception is: " + jmse.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, jmse);
        }

        List<VirtualPrivateCloud> results;
        try {
            long queryStartTime = System.currentTimeMillis();
            results = vpc.query(querySpec, rs);
            long queryTime = System.currentTimeMillis() - queryStartTime;
            int vpcCount = results.size();
            logger.info(LOGTAG + "Queried for VPC metadata in " + queryTime + " ms. Found " + vpcCount + " VPC(s).");
            addResultProperty("vpcCount", Integer.toString(vpcCount));
        } catch (EnterpriseObjectQueryException eoqe) {
            String errMsg = "An error occurred creating the object. The exception is: " + eoqe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, eoqe);
        } finally {
            // Release the producer back to the pool
            getAwsAccountServiceProducerPool().releaseProducer((MessageProducer) rs);
        }

        // If there are results, log them and add them to the result properties.
        if (results.size() > 0) {
            List<String> vpcIds = new ArrayList<>();
            for (VirtualPrivateCloud vpcResult : results) {
                vpcIds.add(vpcResult.getVpcId());
            }
            String vpcIdsCommaSeparated = String.join(",", vpcIds);
            addResultProperty("vpcIds", vpcIdsCommaSeparated);
            logger.info(LOGTAG + "The VpcId(s) are: " + vpcIdsCommaSeparated);
        } else {
            logger.info(LOGTAG + "No VPCs found for accountId: " + accountId);
            addResultProperty("vpcIds", "none");
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
        String LOGTAG = getStepTag() + "[ListVpcIds.simulate] ";
        logger.info(LOGTAG + "Begin step simulation.");

        // Set return properties.
        addResultProperty("stepExecutionMethod", SIMULATED_EXEC_TYPE);
        addResultProperty("vpcIds", "none");

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
        String LOGTAG = getStepTag() + "[ListVpcIds.fail] ";
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

        String LOGTAG = getStepTag() + "[ListVpcIds.rollback] ";
        logger.info(LOGTAG + "Rollback called, nothing to roll back.");

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
