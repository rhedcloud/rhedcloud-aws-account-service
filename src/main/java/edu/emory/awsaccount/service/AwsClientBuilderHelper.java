package edu.emory.awsaccount.service;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.securitytoken.model.AssumeRoleRequest;
import com.amazonaws.services.securitytoken.model.AssumeRoleResult;
import com.amazonaws.services.securitytoken.model.Credentials;

public class AwsClientBuilderHelper {

    /**
     * Amazon EC2 client connected to the correct account with the correct role
     */
    public static AmazonEC2Client buildAmazonEC2Client(String accountId, String region,
                                                       String accessKeyId, String secretKey, String roleArnPattern, int roleAssumptionDurationSeconds) {
        return (AmazonEC2Client) AmazonEC2ClientBuilder.standard()
                .withCredentials(buildSessionCredentials(accountId, region, accessKeyId, secretKey, roleArnPattern,roleAssumptionDurationSeconds))
                .withRegion(region)
                .build();
    }

    /**
     * Build session credentials
     */
    public static AWSCredentialsProvider buildSessionCredentials(String accountId, String region,
                                                                 String accessKeyId, String secretKey,
                                                                 String roleArnPattern, int roleAssumptionDurationSeconds) {
        // use the master account credentials to get the STS client
        BasicAWSCredentials masterCredentials = new BasicAWSCredentials(accessKeyId, secretKey);
        AWSStaticCredentialsProvider cp = new AWSStaticCredentialsProvider(masterCredentials);

        AWSSecurityTokenService sts = AWSSecurityTokenServiceClientBuilder.standard()
                .withCredentials(cp)
                .withRegion(region)
                .build();

        // use the master account to assume a role in the specified account
        AssumeRoleRequest assumeRequest = new AssumeRoleRequest()
                .withRoleArn(roleArnPattern.replace("ACCOUNT_NUMBER", accountId))
                .withDurationSeconds(roleAssumptionDurationSeconds)
                .withRoleSessionName("AwsAccountService");

        AssumeRoleResult assumeResult = sts.assumeRole(assumeRequest);
        Credentials credentials = assumeResult.getCredentials();

        // now build and return the session credentials
        BasicSessionCredentials sessionCredentials = new BasicSessionCredentials(credentials.getAccessKeyId(),
                credentials.getSecretAccessKey(), credentials.getSessionToken());
        return new AWSStaticCredentialsProvider(sessionCredentials);
    }
}
