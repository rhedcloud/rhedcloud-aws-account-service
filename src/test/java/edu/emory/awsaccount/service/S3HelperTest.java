package edu.emory.awsaccount.service;

//import static org.junit.jupiter.api.Assertions.*;

import org.junit.Test;

class S3HelperTest {

    // @BeforeEach
    void setUp() throws Exception {
    }

    @Test
    void testRead() {
        S3Helper helper = new S3Helper(null);
        helper.readDeletedAccounts();
    }

    @Test
    void testUpload() {
        new S3Helper(null).uploadToS3("MobileAppReviewApprovalStat2.jpg", "MobileAppReviewApprovalStat2.jpg");
    }
}
