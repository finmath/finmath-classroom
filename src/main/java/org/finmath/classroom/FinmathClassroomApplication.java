package org.finmath.classroom;

import java.util.Arrays;

import org.finmath.classroom.cli.ClassroomCli;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** CLI by default; starting the loopback-only server requires the explicit serve command. */
@SpringBootApplication
public class FinmathClassroomApplication {
    public static void main(String[] args) {
        if (args.length > 0 && "serve".equals(args[0])) {
            SpringApplication.run(FinmathClassroomApplication.class, Arrays.copyOfRange(args, 1, args.length));
        } else {
            int result = ClassroomCli.run(args, System.out, System.err);
            if (result != 0) {
                System.exit(result);
            }
        }
    }
}
