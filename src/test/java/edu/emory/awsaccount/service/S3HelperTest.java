package edu.emory.awsaccount.service;

//import static org.junit.jupiter.api.Assertions.*;

import org.apache.log4j.Logger;
import org.junit.Before;
import org.junit.Test;

import java.util.Properties;

public class S3HelperTest {
    final static Logger LOG = Logger.getLogger(S3HelperTest.class);
    Properties properties=new Properties();
    String bucketName = "emory-rhedcloud-aws-<env>-accountmetadata";
    String deployEnv="DEV";
    @Before
    public void setUp() throws Exception {
        deployEnv="STAGE";
        properties.setProperty("accessKeyId","***REMOVED***");
        properties.setProperty("secretKey","");
        bucketName=bucketName.replace("<env>",deployEnv.toLowerCase());
        LOG.info("bucketName="+bucketName);
        properties.setProperty("bucketName",bucketName);

    }

    @Test
    public void testRead() {
        S3Helper helper = new S3Helper(properties);
        helper.readDeletedAccounts(deployEnv+"-"+ AccountCsvSyncCommand.deletedAccountsFileName);
    }

    @Test
    public void testUpload() {
        new S3Helper(properties).uploadToS3("GeorgeTest.txt", "README.md");
    }
}
