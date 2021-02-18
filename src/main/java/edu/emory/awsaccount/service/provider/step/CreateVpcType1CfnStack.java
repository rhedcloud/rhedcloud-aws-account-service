/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2017 Emory University. All rights reserved.
 ******************************************************************************/
package edu.emory.awsaccount.service.provider.step;

import com.amazon.aws.moa.jmsobjects.cloudformation.v1_0.Stack;
import com.amazon.aws.moa.objects.resources.v1_0.Credentials;
import com.amazon.aws.moa.objects.resources.v1_0.Output;
import com.amazon.aws.moa.objects.resources.v1_0.Property;
import com.amazon.aws.moa.objects.resources.v1_0.ProvisioningStep;
import com.amazon.aws.moa.objects.resources.v1_0.StackParameter;
import com.amazon.aws.moa.objects.resources.v1_0.StackRequisition;
import com.amazon.aws.moa.objects.resources.v1_0.VirtualPrivateCloudRequisition;
import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;
import org.apache.commons.io.IOUtils;
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
import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Properties;

/**
 * If this is a new account request, build and send a
 * Stack.Generate-Request for the VPC Type 1 CloudFormation Template.
 * <p>
 *
 * @author Steve Wheat (swheat@emory.edu)
 * @version 1.0 - 10 August 2018
 **/
public class CreateVpcType1CfnStack extends AbstractStep implements Step {

    private String m_cloudFormationTemplateUrl = null;
    private String m_cloudFormationTemplateBodyUrl = null;
    private String m_stackName = null;
    private String m_roleArnPattern = null;
    private int m_requestTimeoutInterval = 10000;
    private ProducerPool m_awsAccountServiceProducerPool = null;
    private final static String TEMPLATE_BODY_ENCODING = "UTF-8";

    public void init(String provisioningId, Properties props, AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp)
            throws StepException {

        super.init(provisioningId, props, aConfig, vpcpp);

        String LOGTAG = getStepTag() + "[CreateVpcType1CfnStack.init] ";

        // requestTimeoutInterval is the time to wait for the response to the request
        String timeout = getProperties().getProperty("requestTimeoutInterval", "10000");
        int requestTimeoutInterval = Integer.parseInt(timeout);
        setRequestTimeoutInterval(requestTimeoutInterval);
        logger.info(LOGTAG + "requestTimeoutInterval is: " + getRequestTimeoutInterval());

        // cloudFormationTemplateUrl is the S3 bucket URL of the CloudFormation Template
        String cloudFormationTemplateUrl = getProperties().getProperty("cloudFormationTemplateUrl", null);
        setCloudFormationTemplateUrl(cloudFormationTemplateUrl);
        logger.info(LOGTAG + "cloudFormationTemplateUrl is: " + getCloudFormationTemplateUrl());

        // cloudFormationTemplateBodyUrl is a non S3 URL to the body of the template if an S3 URL cannot be used.
        String cloudFormationTemplateBodyUrl = getProperties().getProperty("cloudFormationTemplateBodyUrl", null);
        setCloudFormationTemplateBodyUrl(cloudFormationTemplateBodyUrl);
        logger.info(LOGTAG + "cloudFormationTemplateBodyUrl is: " + getCloudFormationTemplateBodyUrl());

        // stackName is the name to give the stack.
        String stackName = getProperties().getProperty("stackName", null);
        setStackName(stackName);
        logger.info(LOGTAG + "stackName is: " + getStackName());

        // roleArnPattern to assume a role to perform stack operations
        String roleArnPattern = getProperties().getProperty("roleArnPattern", null);
        setRoleArnPattern(roleArnPattern);
        logger.info(LOGTAG + "roleArnPattern is: " + getRoleArnPattern());

        // This step needs to send messages to the AWS account service to create stacks.
        try {
            ProducerPool p2p1 = (ProducerPool) getAppConfig().getObject("AwsAccountServiceProducerPool");
            setAwsAccountServiceProducerPool(p2p1);
        } catch (EnterpriseConfigurationObjectException ecoe) {
            // An error occurred retrieving an object from AppConfig. Log it and throw an exception.
            String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + ecoe.getMessage();
            logger.fatal(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }

        logger.info(LOGTAG + "Initialization complete.");
    }

    protected List<Property> run() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[CreateVpcType1CfnStack.run] ";
        logger.info(LOGTAG + "Begin running the step.");

        addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);

        // Get the VPCP requisition.
        VirtualPrivateCloudRequisition vpcpr = getVirtualPrivateCloudProvisioning().getVirtualPrivateCloudRequisition();

        boolean createVpc = Boolean.parseBoolean(getStepPropertyValue("DETERMINE_VPC_TYPE", "createVpc"));
        if (!createVpc) {
            logger.info(LOGTAG + "Not creating VPC. No need to create the rhedcloud-aws-vpc-type1 stack.");
            update(COMPLETED_STATUS, SUCCESS_RESULT);

            // Log completion time.
            long time = System.currentTimeMillis() - startTime;
            logger.info(LOGTAG + "Step run completed in " + time + "ms.");

            // Return the properties.
            return getResultProperties();
        }

        if (!vpcpr.getType().equals("1")) {
            // this test is duplicative due to the "createVpc" test above but do it just in case another type is introduced
            logger.info(LOGTAG + "Not creating a type 1 VPC. No need to create the rhedcloud-aws-vpc-type1 stack.");
            update(COMPLETED_STATUS, SUCCESS_RESULT);

            // Log completion time.
            long time = System.currentTimeMillis() - startTime;
            logger.info(LOGTAG + "Step run completed in " + time + "ms.");

            // Return the properties.
            return getResultProperties();
        }

        String vpcConnectionMethod = getStepPropertyValue("DETERMINE_VPC_CONNECTION_METHOD", "vpcConnectionMethod");

        boolean stackCreated = false;

        // accountId can come from one of two different steps depending on if a new account is allocated or not
        String accountId;

        // Get the accountId property from the DETERMINE_NEW_OR_EXISTING_ACCOUNT step.
        ProvisioningStep step_DETERMINE_NEW_OR_EXISTING_ACCOUNT = getProvisioningStepByType("DETERMINE_NEW_OR_EXISTING_ACCOUNT");
        if (step_DETERMINE_NEW_OR_EXISTING_ACCOUNT != null) {
            accountId = getResultProperty(step_DETERMINE_NEW_OR_EXISTING_ACCOUNT, "accountId");
            logger.info(LOGTAG + "Property accountId from the DETERMINE_NEW_OR_EXISTING_ACCOUNT step is: " + accountId);
        } else {
            String errMsg = "Step DETERMINE_NEW_OR_EXISTING_ACCOUNT not found. Cannot determine what the accountId is from this step.";
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }

        // If the existing accountId is null. Get the accountId of the newly generated account.
        if (accountId == null || accountId.equalsIgnoreCase("null")) {
            ProvisioningStep step_GENERATE_NEW_ACCOUNT = getProvisioningStepByType("GENERATE_NEW_ACCOUNT");
            if (step_GENERATE_NEW_ACCOUNT != null) {
                accountId = getResultProperty(step_GENERATE_NEW_ACCOUNT, "newAccountId");
                logger.info(LOGTAG + "Property newAccountId from the GENERATE_NEW_ACCOUNT step is: " + accountId);
            } else {
                String errMsg = "Step GENERATE_NEW_ACCOUNT not found. Cannot determine what the newAccountId is from this step.";
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg);
            }
        }

        if (accountId == null || accountId.equalsIgnoreCase("null")) {
            String errMsg = "The value of accountId could not be found in preceding steps. Can't continue.";
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }

        addResultProperty("accountId", accountId);  // used by future steps

        String transitGatewayId = null;
        String vpn1InsideTunnelCidr1 = null;
        String vpn1InsideTunnelCidr2 = null;
        String vpn1CustomerGatewayIp = null;
        String vpn2InsideTunnelCidr1 = null;
        String vpn2InsideTunnelCidr2 = null;
        String vpn2CustomerGatewayIp = null;

        if (vpcConnectionMethod.equals("VPN")) {
            // Get the VPN inside CIDR properties from the DETERMINE_VPC_CIDR step.
            ProvisioningStep step_DETERMINE_VPC_CIDR = getProvisioningStepByType("DETERMINE_VPC_CIDR");
            if (step_DETERMINE_VPC_CIDR != null) {
                vpn1InsideTunnelCidr1 = getResultProperty(step_DETERMINE_VPC_CIDR, "vpn1InsideTunnelCidr1");
                logger.info(LOGTAG + "Property vpn1InsideTunnelCidr1 from preceding step is: " + vpn1InsideTunnelCidr1);
                addResultProperty("vpn1InsideTunnelCidr1", vpn1InsideTunnelCidr1);

                vpn1InsideTunnelCidr2 = getResultProperty(step_DETERMINE_VPC_CIDR, "vpn1InsideTunnelCidr2");
                logger.info(LOGTAG + "Property vpn1InsideTunnelCidr2 from preceding step is: " + vpn1InsideTunnelCidr2);
                addResultProperty("vpn1InsideTunnelCidr2", vpn1InsideTunnelCidr2);

                vpn1CustomerGatewayIp = getResultProperty(step_DETERMINE_VPC_CIDR, "vpn1CustomerGatewayIp");
                logger.info(LOGTAG + "Property vpn1CustomerGatewayIp from preceding step is: " + vpn1CustomerGatewayIp);
                addResultProperty("vpn1CustomerGatewayIp", vpn1CustomerGatewayIp);

                vpn2InsideTunnelCidr1 = getResultProperty(step_DETERMINE_VPC_CIDR, "vpn2InsideTunnelCidr1");
                logger.info(LOGTAG + "Property vpn2InsideTunnelCidr1 from preceding step is: " + vpn2InsideTunnelCidr1);
                addResultProperty("vpn2InsideTunnelCidr1", vpn2InsideTunnelCidr1);

                vpn2InsideTunnelCidr2 = getResultProperty(step_DETERMINE_VPC_CIDR, "vpn2InsideTunnelCidr2");
                logger.info(LOGTAG + "Property vpn2InsideTunnelCidr2 from preceding step is: " + vpn2InsideTunnelCidr2);
                addResultProperty("vpn2InsideTunnelCidr2", vpn2InsideTunnelCidr2);

                vpn2CustomerGatewayIp = getResultProperty(step_DETERMINE_VPC_CIDR, "vpn2CustomerGatewayIp");
                logger.info(LOGTAG + "Property vpn2CustomerGatewayIp from preceding step is: " + vpn2CustomerGatewayIp);
                addResultProperty("vpn2CustomerGatewayIp", vpn2CustomerGatewayIp);
            } else {
                String errMsg = "Step DETERMINE_VPC_CIDR not found. Cannot determine account sequence number.";
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg);
            }
        }
        else if (vpcConnectionMethod.equals("TGW")) {
            ProvisioningStep step_DETERMINE_VPC_CONNECTION_METHOD = getProvisioningStepByType("DETERMINE_VPC_CONNECTION_METHOD");
            if (step_DETERMINE_VPC_CONNECTION_METHOD != null) {
                transitGatewayId = getResultProperty(step_DETERMINE_VPC_CONNECTION_METHOD, "transitGatewayId");
                logger.info(LOGTAG + "Property transitGatewayId from preceding step is: " + transitGatewayId);
                addResultProperty("transitGatewayId", transitGatewayId);
            } else {
                String errMsg = "Step DETERMINE_VPC_CONNECTION_METHOD not found. Cannot determine transitGatewayId.";
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg);
            }
        }
        else {
            String errMsg = "Unknown VPC connection method (" + vpcConnectionMethod + "). Can't continue.";
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }

        // Get the vpcNetwork property from the COMPUTE_VPC_SUBNETS step.
        ProvisioningStep step_COMPUTE_VPC_SUBNETS = getProvisioningStepByType("COMPUTE_VPC_SUBNETS");
        String vpcNetwork;
        String mgmt1Subnet;
        String mgmt2Subnet;
        String public1Subnet;
        String public2Subnet;
        String private1Subnet;
        String private2Subnet;
        if (step_COMPUTE_VPC_SUBNETS != null) {
            logger.info(LOGTAG + "Step COMPUTE_VPC_SUBNETS found.");

            vpcNetwork = getResultProperty(step_COMPUTE_VPC_SUBNETS, "vpcNetwork");
            logger.info(LOGTAG + "Property vpcNetwork from preceding step is: " + vpcNetwork);
            addResultProperty("vpcNetwork", vpcNetwork);

            mgmt1Subnet = getResultProperty(step_COMPUTE_VPC_SUBNETS, "mgmt1Subnet");
            logger.info(LOGTAG + "Property mgmt1Subnet from preceding step is: " + mgmt1Subnet);
            addResultProperty("mgmt1Subnet", mgmt1Subnet);

            mgmt2Subnet = getResultProperty(step_COMPUTE_VPC_SUBNETS, "mgmt2Subnet");
            logger.info(LOGTAG + "Property mgmt2Subnet from preceding step is: " + mgmt2Subnet);
            addResultProperty("mgmt2Subnet", mgmt2Subnet);

            public1Subnet = getResultProperty(step_COMPUTE_VPC_SUBNETS, "public1Subnet");
            logger.info(LOGTAG + "Property public1Subnet from preceding step is: " + public1Subnet);
            addResultProperty("public1Subnet", public1Subnet);

            public2Subnet = getResultProperty(step_COMPUTE_VPC_SUBNETS, "public2Subnet");
            logger.info(LOGTAG + "Property public2Subnet from preceding step is: " + public2Subnet);
            addResultProperty("public2Subnet", public2Subnet);

            private1Subnet = getResultProperty(step_COMPUTE_VPC_SUBNETS, "private1Subnet");
            logger.info(LOGTAG + "Property private1Subnet from preceding step is: " + private1Subnet);
            addResultProperty("private1Subnet", private1Subnet);

            private2Subnet = getResultProperty(step_COMPUTE_VPC_SUBNETS, "private2Subnet");
            logger.info(LOGTAG + "Property private2Subnet from preceding step is: " + private2Subnet);
            addResultProperty("private2Subnet", private2Subnet);
        } else {
            String errMsg = "Step COMPUTE_VPC_SUBNETS not found. Cannot determine subnet properties.";
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }

        // Get the VPC sequence number.
        String vpcSequenceNumber = getStepPropertyValue("DETERMINE_NEW_VPC_SEQUENCE_VALUE", "vpcSequenceNumber");
        logger.info(LOGTAG + "Property vpcSequenceNumber from preceding step is: " + vpcSequenceNumber);
        addResultProperty("vpcSequenceNumber", vpcSequenceNumber);
        if (vpcSequenceNumber == null) {
            String errMsg = "VPC sequence number not found in preceding step. Cannot proceed.";
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }

        // Update the step, so the parameters are visible for execution.
        update(IN_PROGRESS_STATUS, NO_RESULT);

        logger.info(LOGTAG + "Sending a Stack.Generate-Request to create the rhedcloud-aws-vpc-type1 stack in the account.");

        Stack stack = new Stack();
        StackRequisition req = new StackRequisition();
        try {
            stack = (Stack) getAppConfig().getObjectByType(stack.getClass().getName());
            req = (StackRequisition) getAppConfig().getObjectByType(req.getClass().getName());
        } catch (EnterpriseConfigurationObjectException ecoe) {
            String errMsg = "An error occurred retrieving an object from AppConfig. The exception is: " + ecoe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, ecoe);
        }

        // Set the values of the requisition and place them in step props.
        try {
            // AccountId
            req.setAccountId(accountId);

            // Region
            req.setRegion(vpcpr.getRegion());
            addResultProperty("region", req.getRegion());
            logger.info(LOGTAG + "Region is: " + req.getRegion());

            // StackName
            req.setStackName(getStackName() + "-" + vpcSequenceNumber);
            addResultProperty("stackName", req.getStackName());
            logger.info(LOGTAG + "stackName: " + req.getStackName());

            // Credential, presently used to pass the roleArnPattern to assume a role to create the stack.
            Credentials creds = req.newCredentials();
            creds.setAccessKeyId("roleArnPattern");
            creds.setSecretKey(getRoleArnPattern());
            req.setCredentials(creds);

            // Description
            req.setDescription("RHEDcloud AWS CloudFormation template for type 1 vpc-level structures and policies");

            // DisableRollback
            req.setDisableRollback("false");

            // Template URL - we prefer to pull this from an S3 bucket, but if we have to, we read it from a non-S3 URL.
            if (getCloudFormationTemplateUrl() != null) {
                req.setTemplateUrl(getCloudFormationTemplateUrl());
                addResultProperty("templateUrl", req.getTemplateUrl());
                logger.info(LOGTAG + "templateUrl: " + req.getTemplateUrl());
            } else if (getCloudFormationTemplateBodyUrl() != null) {
                req.setTemplateBody(getCloudFormationTemplateBody());
                addResultProperty("templateBodyUrl", getCloudFormationTemplateBodyUrl());
                logger.info(LOGTAG + "templateBody: " + req.getTemplateBody());
            } else {
                String errMsg = "No CloudFormation template source specified. Can't continue.";
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg);
            }

            // Set stack parameters
            logger.info(LOGTAG + "Setting stack parameters...");

            // Parameter 1 - VpcCidr
            StackParameter parameter1 = req.newStackParameter();
            parameter1.setKey("VpcCidr");
            parameter1.setValue(vpcNetwork);
            req.addStackParameter(parameter1);

            // Parameter 2 - ManagementSubnet1Cidr
            StackParameter parameter2 = req.newStackParameter();
            parameter2.setKey("ManagementSubnet1Cidr");
            parameter2.setValue(mgmt1Subnet);
            req.addStackParameter(parameter2);

            // Parameter 3 - ManagementSubnet2Cidr
            StackParameter parameter3 = req.newStackParameter();
            parameter3.setKey("ManagementSubnet2Cidr");
            parameter3.setValue(mgmt2Subnet);
            req.addStackParameter(parameter3);

            // Parameter 4 - PublicSubnet1Cidr
            StackParameter parameter4 = req.newStackParameter();
            parameter4.setKey("PublicSubnet1Cidr");
            parameter4.setValue(public1Subnet);
            req.addStackParameter(parameter4);

            // Parameter 5 - PublicSubnet2Cidr
            StackParameter parameter5 = req.newStackParameter();
            parameter5.setKey("PublicSubnet2Cidr");
            parameter5.setValue(public2Subnet);
            req.addStackParameter(parameter5);

            // Parameter 6 - PrivateSubnet1Cidr
            StackParameter parameter6 = req.newStackParameter();
            parameter6.setKey("PrivateSubnet1Cidr");
            parameter6.setValue(private1Subnet);
            req.addStackParameter(parameter6);

            // Parameter 7 - PrivateSubnet2Cidr
            StackParameter parameter7 = req.newStackParameter();
            parameter7.setKey("PrivateSubnet2Cidr");
            parameter7.setValue(private2Subnet);
            req.addStackParameter(parameter7);

            if (vpcConnectionMethod.equals("VPN")) {
                StackParameter p = req.newStackParameter();
                p.setKey("VpcConnectionMethod");
                p.setValue("VPN");
                req.addStackParameter(p);

                // Parameter 8 - RHEDcloudVpn1InsideTunnelCidr1
                StackParameter parameter8 = req.newStackParameter();
                parameter8.setKey("RHEDcloudVpn1InsideTunnelCidr1");
                parameter8.setValue(vpn1InsideTunnelCidr1);
                req.addStackParameter(parameter8);

                // Parameter 9 - RHEDcloudVpn1InsideTunnelCidr2
                StackParameter parameter9 = req.newStackParameter();
                parameter9.setKey("RHEDcloudVpn1InsideTunnelCidr2");
                parameter9.setValue(vpn1InsideTunnelCidr2);
                req.addStackParameter(parameter9);

                // Parameter 10 - RHEDcloudVpn2InsideTunnelCidr1
                StackParameter parameter10 = req.newStackParameter();
                parameter10.setKey("RHEDcloudVpn2InsideTunnelCidr1");
                parameter10.setValue(vpn2InsideTunnelCidr1);
                req.addStackParameter(parameter10);

                // Parameter 11 - RHEDcloudVpn2InsideTunnelCidr2
                StackParameter parameter11 = req.newStackParameter();
                parameter11.setKey("RHEDcloudVpn2InsideTunnelCidr2");
                parameter11.setValue(vpn2InsideTunnelCidr2);
                req.addStackParameter(parameter11);

                // Parameter 12 - RHEDcloud1CustomerGatewayIp
                StackParameter parameter12 = req.newStackParameter();
                parameter12.setKey("RHEDcloud1CustomerGatewayIp");
                parameter12.setValue(vpn1CustomerGatewayIp);
                req.addStackParameter(parameter12);

                // Parameter 13 - RHEDcloud2CustomerGatewayIp
                StackParameter parameter13 = req.newStackParameter();
                parameter13.setKey("RHEDcloud2CustomerGatewayIp");
                parameter13.setValue(vpn2CustomerGatewayIp);
                req.addStackParameter(parameter13);
            }
            else { // TGW
                StackParameter p = req.newStackParameter();
                p.setKey("VpcConnectionMethod");
                p.setValue("TGW");
                req.addStackParameter(p);

                p = req.newStackParameter();
                p.setKey("TransitGatewayId");
                p.setValue(transitGatewayId);
                req.addStackParameter(p);
            }

            // Add capabilities
            req.addCapability("CAPABILITY_IAM");
            req.addCapability("CAPABILITY_NAMED_IAM");


            // Log stack information
            @SuppressWarnings("unchecked")
            List<StackParameter> params = req.getStackParameter();
            for (StackParameter param : params) {
                logger.info(LOGTAG + "StackParameter " + param.getKey() + ": " + param.getValue());
            }
            @SuppressWarnings("unchecked")
            List<String> capabilities = req.getCapability();
            for (String capability : capabilities) {
                logger.info(LOGTAG + "Capability: " + capability);
            }

        } catch (EnterpriseFieldException efe) {
            String errMsg = "An error occurred setting the values of the requisition. The exception is: " + efe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, efe);
        }

        // Log the state of the requisition.
        try {
            logger.info(LOGTAG + "Requisition is: " + req.toXmlString());
        } catch (XmlEnterpriseObjectException xeoe) {
            String errMsg = "An error occurred serializing the requisition to XML. The exception is: " + xeoe.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, xeoe);
        }

        // TODO:Set the message authentication
        // Authentication auth = stack.getAuthentication();
        // auth.setAuthUserId(userId);

        // Get a request service from the pool and set the timeout interval.
        RequestService rs;
        try {
            PointToPointProducer p2p = (PointToPointProducer) getAwsAccountServiceProducerPool().getExclusiveProducer();
            p2p.setRequestTimeoutInterval(getRequestTimeoutInterval());
            rs = p2p;
        } catch (JMSException jmse) {
            String errMsg = "An error occurred getting a producer from the pool. The exception is: " + jmse.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, jmse);
        }

        List results;
        try {
            long generateStartTime = System.currentTimeMillis();
            logger.info(LOGTAG + "Sending the Stack.Generate-Request...");
            results = stack.generate(req, rs);
            long generateTime = System.currentTimeMillis() - generateStartTime;
            logger.info(LOGTAG + "Generated CloudFormation Stack in " + generateTime + " ms. Returned " + results.size() + " result.");
        } catch (EnterpriseObjectGenerateException eoge) {
            String errMsg = "An error occurred generating the Stack object. The exception is: " + eoge.getMessage();
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg, eoge);
        } finally {
            // Release the producer back to the pool
            getAwsAccountServiceProducerPool().releaseProducer((MessageProducer) rs);
        }

        if (results.size() == 1) {
            Stack stackResult = (Stack) results.get(0);
            logger.info(LOGTAG + "Stack status is: " + stackResult.getStackStatus());
            addResultProperty("stackStatus", stackResult.getStackStatus());
            if (stackResult.getStackStatus().equalsIgnoreCase("CREATE_COMPLETE")) {
                stackCreated = true;
            }

            // Get the outputs and add them as result properties.
            @SuppressWarnings("unchecked")
            List<Output> outputs = stackResult.getOutput();
            if (outputs != null) {
                for (Output o : outputs) {
                    addResultProperty(o.getOutputKey(), o.getOutputValue());
                    logger.info(LOGTAG + "CloudFormation Template Output: " + o.getOutputKey() + "=" + o.getOutputValue());
                }
            }
        } else {
            String errMsg = "Invalid number of results returned from Stack.Generate-Request. " + results.size()
                    + " results returned. Expected exactly 1.";
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }

        // Update the step.
        update(COMPLETED_STATUS, stackCreated ? SUCCESS_RESULT : FAILURE_RESULT);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step run completed in " + time + "ms.");

        // Return the properties.
        return getResultProperties();
    }

    protected List<Property> simulate() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[CreateVpcType1CfnStack.simulate] ";
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
        String LOGTAG = getStepTag() + "[CreateVpcType1CfnStack.fail] ";
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

        String LOGTAG = getStepTag() + "[CreateVpcType1CfnStack.rollback] ";
        logger.info(LOGTAG + "Rollback called, but this step has nothing to roll back.");
        update(ROLLBACK_STATUS, SUCCESS_RESULT);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
    }

    private void setRequestTimeoutInterval(int i) {
        m_requestTimeoutInterval = i;
    }

    private int getRequestTimeoutInterval() {
        return m_requestTimeoutInterval;
    }

    private void setAwsAccountServiceProducerPool(ProducerPool pool) {
        m_awsAccountServiceProducerPool = pool;
    }

    private ProducerPool getAwsAccountServiceProducerPool() {
        return m_awsAccountServiceProducerPool;
    }

    private void setCloudFormationTemplateUrl(String url) {
        m_cloudFormationTemplateUrl = url;
    }

    private String getCloudFormationTemplateUrl() {
        return m_cloudFormationTemplateUrl;
    }

    private void setCloudFormationTemplateBodyUrl(String url) {
        m_cloudFormationTemplateBodyUrl = url;
    }

    private String getCloudFormationTemplateBodyUrl() {
        return m_cloudFormationTemplateBodyUrl;
    }

    private void setStackName(String name) throws StepException {
        if (name == null) {
            String errMsg = "stackName property is null. Can't continue.";
            throw new StepException(errMsg);
        }
        m_stackName = name;
    }

    private String getStackName() {
        return m_stackName;
    }

    private void setRoleArnPattern(String pattern) throws StepException {
        if (pattern == null) {
            String errMsg = "roleArnPattern property is null. Can't continue.";
            throw new StepException(errMsg);
        }
        m_roleArnPattern = pattern;
    }

    private String getRoleArnPattern() {
        return m_roleArnPattern;
    }

    private String getCloudFormationTemplateBody() throws StepException {
        String LOGTAG = getStepTag() + "[CreateVpcType1CfnStack.getCloudFormationTemplateBody] ";
        String templateBody;

        if (getCloudFormationTemplateBodyUrl() != null) {
            try {
                URL url = new URL(getCloudFormationTemplateBodyUrl());
                templateBody = IOUtils.toString(url, TEMPLATE_BODY_ENCODING);
                return templateBody;
            } catch (IOException ioe) {
                String errMsg = "An error occurred reading the CloudFormation template body by URL. The exception is: " + ioe.getMessage();
                logger.error(LOGTAG + errMsg);
                throw new StepException(errMsg);
            }
        } else {
            String errMsg = "CloudFormation template body URL is null. Can't continue.";
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }
    }
}
