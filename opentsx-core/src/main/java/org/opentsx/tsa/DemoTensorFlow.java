package org.opentsx.tsa;

import org.tensorflow.Graph;
import org.tensorflow.Session;
import org.tensorflow.Tensor;
import org.tensorflow.TensorFlow;

/**
 *
 * PREP TF-Framework on your host ...
 *
 *  pip3 install tensorflow==1.9.0  (for Mac OSX 10.11.6)
 *  pip3 install tensorflow-gpu==1.9.0  (for Mac OSX 10.11.6)
 *
 */

import java.io.UnsupportedEncodingException;

/**
 * Created by kamir on 20.11.18.
 */
public class DemoTensorFlow {

    public static void main(String[] ARGS) throws UnsupportedEncodingException {


        try (Graph g = new Graph()) {

            final String value = "Hello from " + TensorFlow.version();
            // Construct the computation graph with a single operation, a constant
            // named "MyConst" with a value "value".
            try (Tensor t = Tensor.create(value.getBytes("UTF-8"))) {
                // The Java API doesn't yet include convenience functions for adding operations.
                g.opBuilder("Const", "MyConst").setAttr("dtype", t.dataType()).setAttr("value", t).build();
            }
            // Execute the "MyConst" operation in a Session.
            try (Session s = new Session(g);
                 Tensor output = s.runner().fetch("MyConst").run().get(0)) {
                 System.out.println(new String(output.bytesValue(), "UTF-8"));
            }

        }

        System.out.println("Done.");

    }


}
