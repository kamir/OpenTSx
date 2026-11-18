package org.opentsx.wayang;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.opentsx.kafka.KafScaleClients;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class RustFencingComposeFixture {
    static final String RESTARTED_OUTPUT = "tsx.rust-fencing-restarted.clean.v2";

    private RustFencingComposeFixture() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--help")) {
            RustContinuousComposeFixture.main(args);
            System.out.println("verify <bootstrap> <fixture-directory> <deployment.json> <submitted-dag.txt> <through-block-count> " + RESTARTED_OUTPUT);
        } else if (args.length == 3 && args[0].equals("prepare")) {
            RustContinuousComposeFixture.main(args);
            try (Admin admin = KafScaleClients.admin(args[1])) {
                admin.createTopics(List.of(new NewTopic(RESTARTED_OUTPUT, 3, (short) 1))).all().get(180, TimeUnit.SECONDS);
            }
        } else if (args.length == 7 && args[0].equals("verify")) {
            if (!args[6].equals(RESTARTED_OUTPUT)) throw new IllegalArgumentException("unexpected fencing replacement output topic");
            RustContinuousComposeFixture.verify(args[1], Path.of(args[2]), Path.of(args[3]), Path.of(args[4]),
                    RustContinuousComposeFixture.blocks(args[5]), RESTARTED_OUTPUT);
        } else {
            RustContinuousComposeFixture.main(args);
        }
    }
}
