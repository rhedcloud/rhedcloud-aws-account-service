/*******************************************************************************
 $Source: $
 $Revision: $
 *******************************************************************************/

/******************************************************************************
 This file is part of the Emory AWS Account Service.

 Copyright (C) 2017 Emory University. All rights reserved.
 ******************************************************************************/
package edu.emory.awsaccount.service.provider.step;

import com.amazon.aws.moa.objects.resources.v1_0.Property;
import edu.emory.awsaccount.service.provider.VirtualPrivateCloudProvisioningProvider;
import org.apache.commons.net.util.SubnetUtils;
import org.apache.commons.net.util.SubnetUtils.SubnetInfo;
import org.openeai.config.AppConfig;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * This step executes the subnetting algorithm and stores the results as step properties.
 */
public class ComputeVpcSubnets extends AbstractStep implements Step {
    public void init(String provisioningId, Properties props, AppConfig aConfig, VirtualPrivateCloudProvisioningProvider vpcpp) throws StepException {
        super.init(provisioningId, props, aConfig, vpcpp);

        String LOGTAG = getStepTag() + "[ComputeVpcSubnets.init] ";
        logger.info(LOGTAG + "Initialization complete.");
    }

    protected List<Property> run() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[ComputeVpcSubnets.run] ";
        logger.info(LOGTAG + "Begin running the step.");

        // Return properties
        addResultProperty("stepExecutionMethod", RUN_EXEC_TYPE);

        boolean createVpc = Boolean.parseBoolean(getStepPropertyValue("DETERMINE_VPC_TYPE", "createVpc"));
        String vpcConnectionMethod = getStepPropertyValue("DETERMINE_VPC_CONNECTION_METHOD", "vpcConnectionMethod");
        String applicableVpcNetwork;
        boolean reasonGiven = false;

        if (!createVpc) {
            logger.info(LOGTAG + "Bypassing VPC subnet determination since no VPC is being created");
            applicableVpcNetwork = "not applicable";
            reasonGiven = true;
        } else if (vpcConnectionMethod.equals("VPN")) {
            applicableVpcNetwork = getStepPropertyValue("DETERMINE_VPC_CIDR", "vpcNetwork");
        } else if (vpcConnectionMethod.equals("TGW")) {
            applicableVpcNetwork = getStepPropertyValue("DETERMINE_VPC_TGW_CIDR", "vpcNetwork");
        } else {
            String errMsg = "Error during VPC subnet determination due to unknown VPC connection method: " + vpcConnectionMethod;
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        }

        if (applicableVpcNetwork.equals("not available")) {
            // when getStepPropertyValue() gets a null or empty value
            String errMsg = "Error during VPC subnet determination due to unknown VPC network";
            logger.error(LOGTAG + errMsg);
            throw new StepException(errMsg);
        } else if (applicableVpcNetwork.equals("not applicable")) {
            if (!reasonGiven)
                logger.info(LOGTAG + "Bypassing VPC subnet determination since VPC network is not applicable");
        } else {
            // Begin pseudocode provided by Paul Petersen. Modified for proper syntax.
            String originalCidr = applicableVpcNetwork;

            logger.info(LOGTAG + "Proceeding with VPC subnet determination with originalCidr (vpcNetwork): " + originalCidr);

            String[] originalCidrArray = originalCidr.split("/");
            String originalCidrNetwork = originalCidrArray[0];
            String originalCidrBits = originalCidrArray[1];
            logger.info(LOGTAG + "originalCidrBits is: " + originalCidrBits);
            int bits = Integer.parseInt(originalCidrBits);

            String mgmtPubMask = Integer.toString(bits + 3);
            logger.info(LOGTAG + "mgmtPubMask is: " + mgmtPubMask);
            addResultProperty("mgmtPubMask", mgmtPubMask);

            String privMask = Integer.toString(bits + 2);
            logger.info(LOGTAG + "privMask is: " + privMask);
            addResultProperty("privMask", privMask);

            String mgmt1Subnet = originalCidrNetwork + "/" + mgmtPubMask;
            logger.info(LOGTAG + "mgmt1Subnet is: " + mgmt1Subnet);
            addResultProperty("mgmt1Subnet", mgmt1Subnet);

            String mgmt2Subnet = getNextSubnet(mgmt1Subnet, mgmtPubMask);
            logger.info(LOGTAG + "mgmt2Subnet is: " + mgmt2Subnet);
            addResultProperty("mgmt2Subnet", mgmt2Subnet);

            String public1Subnet = getNextSubnet(mgmt2Subnet, mgmtPubMask);
            logger.info(LOGTAG + "public1Subnet is: " + public1Subnet);
            addResultProperty("public1Subnet", public1Subnet);

            String public2Subnet = getNextSubnet(public1Subnet, mgmtPubMask);
            logger.info(LOGTAG + "public2Subnet is: " + public2Subnet);
            addResultProperty("public2Subnet", public2Subnet);

            String[] private1NetworkArray = getNextSubnet(public2Subnet, mgmtPubMask).split("/");
            logger.info(LOGTAG + "private1NetworkArray is: " + Arrays.toString(private1NetworkArray));
            String private1Network = private1NetworkArray[0];
            logger.info(LOGTAG + "private1Network is: " + private1Network);

            String private1Subnet = private1Network + "/" + privMask;
            logger.info(LOGTAG + "private1Subnet is: " + private1Subnet);
            addResultProperty("private1Subnet", private1Subnet);

            String private2Subnet = getNextSubnet(private1Subnet, privMask);
            logger.info(LOGTAG + "private2Subnet is: " + private2Subnet);
            addResultProperty("private2Subnet", private2Subnet);

            // End pseudocode provided by Paul Petersen.
        }

        // Update the step.
        update(COMPLETED_STATUS, SUCCESS_RESULT);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Step run completed in " + time + "ms.");

        // Return the properties.
        return getResultProperties();
    }

    /*
     * this can be used to run this step standalone to compute VPC subnets
     */
    /*
    public static void main(String[] args) {
        String[] originalCidrArray = "10.66.142.0/23".split("/");
        String originalCidrNetwork = originalCidrArray[0];
        String originalCidrBits = originalCidrArray[1];
        int bits = Integer.parseInt(originalCidrBits);

        String mgmtPubMask =  Integer.toString(bits + 3);
        String privMask = Integer.toString(bits + 2);

        String mgmt1Subnet = originalCidrNetwork + "/" + mgmtPubMask;
        System.out.println("mgmt1Subnet " + mgmt1Subnet);

        String mgmt2Subnet = getNextSubnet(mgmt1Subnet, mgmtPubMask);
        System.out.println("mgmt2Subnet " + mgmt2Subnet);

        String public1Subnet = getNextSubnet(mgmt2Subnet, mgmtPubMask);
        System.out.println("public1Subnet " + public1Subnet);

        String public2Subnet = getNextSubnet(public1Subnet, mgmtPubMask);
        System.out.println("public2Subnet " + public2Subnet);

        String[] private1NetworkArray = getNextSubnet(public2Subnet, mgmtPubMask).split("/");
        String private1Network = private1NetworkArray[0];

        String private1Subnet = private1Network + "/" + privMask;
        System.out.println("private1Subnet " + private1Subnet);

        String private2Subnet = getNextSubnet(private1Subnet, privMask);
        System.out.println("private2Subnet " + private2Subnet);
    }
    */

    protected List<Property> simulate() throws StepException {
        long startTime = System.currentTimeMillis();
        String LOGTAG = getStepTag() + "[ComputeVpcSubnets.simulate] ";
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
        String LOGTAG = getStepTag() + "[ComputeVpcSubnets.fail] ";
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

        String LOGTAG = getStepTag() + "[ComputeVpcSubnets.rollback] ";
        logger.info(LOGTAG + "Rollback called, but this step has nothing to roll back.");

        update(ROLLBACK_STATUS, SUCCESS_RESULT);

        // Log completion time.
        long time = System.currentTimeMillis() - startTime;
        logger.info(LOGTAG + "Rollback completed in " + time + "ms.");
    }

    // method provided by Paul Petersen.
    private String nextIpAddress(final String input) {
        final String[] tokens = input.split("\\.");
        if (tokens.length != 4)
            throw new IllegalArgumentException();
        for (int i = tokens.length - 1; i >= 0; i--) {
            final int item = Integer.parseInt(tokens[i]);
            if (item < 255) {
                tokens[i] = String.valueOf(item + 1);
                for (int j = i + 1; j < 4; j++) {
                    tokens[j] = "0";
                }
                break;
            }
        }
        return tokens[0] + '.' + tokens[1] + '.' + tokens[2] + '.' + tokens[3];
    }

    // method pseudocode provided by Paul Petersen
    private String getNextSubnet(String inputSubnet, String bits) {
        SubnetUtils utils = new SubnetUtils(inputSubnet);
        SubnetInfo info = utils.getInfo();
        String bcastIpAddress = info.getBroadcastAddress();
        String nextNetwork = nextIpAddress(bcastIpAddress);
        return nextNetwork + "/" + bits;
    }

    private String addToNetmask(String netmask, int i) {
        String LOGTAG = getStepTag() + "[ComputeVpcSubnets.addToNetMask] ";
        logger.info(LOGTAG + "netmask: " + netmask);
        String[] octets = netmask.split("\\.");
        logger.info(LOGTAG + "octets: " + octets[0] + " " + octets[1] + " " + octets[2] + " " + octets[3]);
        String lastOctet = octets[3];
        logger.info(LOGTAG + "lastOctet: " + lastOctet);
        int o = Integer.parseInt(lastOctet);
        o = o + i;
        lastOctet = Integer.toString(o);
        String newNetmask = octets[0] + "." + octets[1] + "." + octets[2] + "." + lastOctet;
        logger.info(LOGTAG + "newNetmask: " + newNetmask);
        return newNetmask;
    }
}
