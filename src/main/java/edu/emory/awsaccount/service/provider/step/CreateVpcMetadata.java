/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2017 Emory University. All rights reserved.
 ******************************************************************************/
package edu.emory.awsaccount.service.provider.step;

import com.amazon.aws.moa.jmsobjects.provisioning.v1_0.VirtualPrivateCloud;
import com.amazon.aws.moa.objects.resources.v1_0.Datetime;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudQuerySpecification;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudRequisition;
import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;
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

/**
 * Create VPC metadata to go along with the new VPC in the account.
 * <p>
 *
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 30 August 2018
 **/
public class CreateVpcMetadata extends AbstractStep implements Step {

    private ProducerPool m_awsAccountServiceProducerPool = null;

    public void init(String provisioningId, Properties props,
                     AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp)
            throws StepException {

        super.init(provisioningId, props, aConfig, vpcpp);

        String LOGTAG = getStepTag() + "[CreateVpcMetadata.init] ";

        // This step needs to send messages to the AWS account service
        // to create account metadata.
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
        String LOGTAG = getStepTag() + "[CreateAccountMetadata.run] ";
        logger.info(LOGTAG + "Begin running the step.");

        addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);

        boolean createVpc = Boolean.parseBoolean(getStepPropertyValue("DETERMINE_VPC_TYPE", "createVpc"));
        String vpcConnectionMethod = getStepPropertyValue("DETERMINE_VPC_CONNECTION_METHOD", "vpcConnectionMethod");

        if (!createVpc) {
            logger.info(LOGTAG + "Bypassing VPC metadata creation since no VPC is being created");
        }
        else {
            // Get the requisition
            VirtualPrivateCloudRequisition req = getVirtualPrivateCloudProvisioning().getVirtualPrivateCloudRequisition();

            // Get some properties from previous steps.
            String accountId = getStepPropertyValue("GENERATE_NEW_ACCOUNT", "newAccountId");
            if (accountId.equals(PROPERTY_VALUE_NOT_APPLICABLE) || accountId.equals(PROPERTY_VALUE_NOT_AVAILABLE)) {
                accountId = req.getAccountId();
                if (accountId == null || accountId.equals("")) {
                    String errMsg = "No account number for the new VPC can be found. Can't continue.";
                    logger.error(LOGTAG + errMsg);
                    throw new StepException(errMsg);
                }
            }

            String vpcId = getStepPropertyValue("CREATE_VPC_TYPE1_CFN_STACK", "VpcId");
            String region = req.getRegion();
            String vpcType = req.getType();
            String vpcCidr;
            String referenceId;

            if (vpcConnectionMethod.equals("VPN")) {
                vpcCidr = getStepPropertyValue("DETERMINE_VPC_CIDR", "vpcNetwork");
                referenceId = getStepPropertyValue("DETERMINE_VPC_CIDR", "vpnConnectionProfileId");
            } else if (vpcConnectionMethod.equals("TGW")) {
                vpcCidr = getStepPropertyValue("DETERMINE_VPC_TGW_CIDR", "vpcNetwork");
                referenceId = getStepPropertyValue("DETERMINE_VPC_CONNECTION_METHOD", "transitGatewayId");
            } else {
                String errMsg = "Error during VPC metadata creation due to unknown VPC connection method: " + vpcConnectionMethod;
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg);
            }


            // Get a configured VPC object from AppConfig.
            VirtualPrivateCloud vpc = new VirtualPrivateCloud();
            try {
                vpc = (VirtualPrivateCloud) getAppConfig().getObjectByType(vpc.getClass().getName());
            } catch (EnterpriseConfigurationObjectException ecoe) {
                String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + ecoe.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg, ecoe);
            }

            // Set the values of the VPC.
            try {
                vpc.setAccountId(accountId);
                vpc.setVpcId(vpcId);
                vpc.setRegion(region);
                vpc.setType(vpcType);
                vpc.setCidr(vpcCidr);
                vpc.setVpcConnectionMethod(vpcConnectionMethod);
                vpc.setReferenceId(referenceId);
                vpc.setPurpose(req.getPurpose());
                vpc.setCreateUser(req.getAuthenticatedRequestorUserId());
                vpc.setCreateDatetime(new Datetime("Create", System.currentTimeMillis()));
            } catch (EnterpriseFieldException efe) {
                String errMsg = "An error occurred setting the values of the VirtualPrivateCloud object. The exception is: " + efe.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg, efe);
            }

            try {
                logger.info(LOGTAG + "VPC to create is: " + vpc.toXmlString());
            } catch (XmlEnterpriseObjectException xeoe) {
                String errMsg = "An error occurred serializing the VirtualPrivateCloud to XML. The exception is: " + xeoe.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg, xeoe);
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

            try {
                long createStartTime = System.currentTimeMillis();
                vpc.create(rs);
                long createTime = System.currentTimeMillis() - createStartTime;
                logger.info(LOGTAG + "VirtualPrivateCloud.Create took " + createTime + " ms.");
                addResultProperty("vpcMetadataCreated", "true");
                addResultProperty("accountId", accountId);
                addResultProperty("vpcId", vpcId);
                addResultProperty("region", region);
                addResultProperty("vpcType", vpcType);
                addResultProperty("vpcCidr", vpcCidr);
                addResultProperty("referenceId", vpc.getReferenceId());
            } catch (EnterpriseObjectCreateException e) {
                String errMsg = "An error occurred creating the object. The exception is: " + e.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg, e);
            } finally {
                getAwsAccountServiceProducerPool().releaseProducer((MessageProducer) rs);
            }
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
        String LOGTAG = getStepTag() + "[CreateVpcMetadata.simulate] ";
        logger.info(LOGTAG + "Begin step simulation.");

        // Set return properties.
        addResultProperty("stepExecutionMethod", SIMULATED_EXEC_TYPE);
        addResultProperty("vpcMetadataCreated", "false");

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
        String LOGTAG = getStepTag() + "[CreateVpcMetadata.fail] ";
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

        String LOGTAG = getStepTag() + "[CreateVpcMetadata.rollback] ";
        logger.info(LOGTAG + "Rollback called, deleting VPC metadata.");

        // Get the VpcId
        String vpcId = getResultProperty("vpcId");

        // If the vpcId is not null, query for the VPC object and then delete it.
        if (vpcId != null) {
            // Query for the VPC
            // Get a configured VPC object and account query spec from AppConfig.
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

            // Set the values of the query spec
            try {
                querySpec.setVpcId(vpcId);
            } catch (EnterpriseFieldException efe) {
                String errMsg = "An error occurred setting a field value. The exception is: " + efe.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException();
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

            // Query for the account metadata
            List results;
            try {
                long queryStartTime = System.currentTimeMillis();
                results = vpc.query(querySpec, rs);
                long createTime = System.currentTimeMillis() - queryStartTime;
                logger.info(LOGTAG + "Queried for VPC in " + createTime + " ms. Got " + results.size() + " result(s).");
            } catch (EnterpriseObjectQueryException eoqe) {
                String errMsg = "An error occurred querying for the object. The exception is: " + eoqe.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg, eoqe);
            } finally {
                getAwsAccountServiceProducerPool().releaseProducer((MessageProducer) rs);
            }

            // If there is a result, delete the VPC metadata
            if (results.size() > 0) {
                vpc = (VirtualPrivateCloud) results.get(0);

                // Get a producer from the pool
                try {
                    rs = (RequestService) getAwsAccountServiceProducerPool().getExclusiveProducer();
                } catch (JMSException jmse) {
                    String errMsg = "An error occurred getting a producer from the pool. The exception is: " + jmse.getMessage();
                    logger.error(LOGTAG + errMsg);
                    throw new StepException(errMsg, jmse);
                }

                // Delete the VPC metadata
                try {
                    long deleteStartTime = System.currentTimeMillis();
                    vpc.delete("Delete", rs);
                    long deleteTime = System.currentTimeMillis() - deleteStartTime;
                    logger.info(LOGTAG + "Deleted VPC in " + deleteTime + " ms. Got " + results.size() + " result(s).");
                    addResultProperty("deletedVpcMetadataOnRollback", "true");
                } catch (EnterpriseObjectDeleteException eode) {
                    String errMsg = "An error occurred deleting the object. The exception is: " + eode.getMessage();
                    logger.error(LOGTAG + errMsg);
                    throw new StepException(errMsg, eode);
                } finally {
                    getAwsAccountServiceProducerPool().releaseProducer((MessageProducer) rs);
                }
            }
        }
        // If vpcId is null, there is nothing to roll back. Log it.
        else {
            logger.info(LOGTAG + "No VPC metadata was created by this step, so there is nothing to roll back.");
            addResultProperty("deletedVpcMetadataOnRollback", "not applicable");
        }

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
