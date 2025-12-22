/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.opentsx.net.clusterscan;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Vector;

/**
 * IP Range Scanner: Utility for scanning IP address ranges to detect reachable cluster nodes.
 *
 * <p>This class provides functionality to scan IP address ranges using CIDR notation
 * and identify which nodes are reachable on the network. It's particularly useful for
 * cluster administrators who need to verify node availability and discover active hosts.
 *
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * // Scan a /24 network (256 addresses)
 * ScanIPS scanner = new ScanIPS();
 * Vector<InetAddress> liveNodes = scanner.scanRange("192.168.1.0/24");
 *
 * // Print all reachable nodes
 * for (InetAddress node : liveNodes) {
 *     System.out.println("Found: " + node.getHostAddress());
 * }
 * }</pre>
 *
 * <h2>CIDR Notation Support:</h2>
 * <ul>
 *   <li>/8 - Class A (16,777,216 addresses)</li>
 *   <li>/16 - Class B (65,536 addresses)</li>
 *   <li>/24 - Class C (256 addresses) - most common for LANs</li>
 *   <li>/32 - Single host</li>
 * </ul>
 *
 * <h2>Performance Considerations:</h2>
 * <p>Each address is tested with a 100ms reachability timeout. Large ranges can take
 * significant time to scan:
 * <ul>
 *   <li>/24 network: ~25 seconds maximum</li>
 *   <li>/16 network: ~1.8 hours maximum (not recommended for synchronous scanning)</li>
 * </ul>
 *
 * <h2>Network Requirements:</h2>
 * <p>Requires ICMP (ping) to be enabled on target hosts and allowed through firewalls.
 * Some hosts may have ping disabled for security reasons and will appear unreachable
 * even if they are online.
 *
 * @author kamir
 * @version 1.0.0
 * @see InetAddress#isReachable(int)
 * @see ClusterCheck
 * @since OpenTSx 3.0.0
 */
public class ScanIPS {

    /**
     * Main method demonstrating IP range scanning functionality.
     *
     * <p>This example scans the 192.168.3.0/24 network and prints all reachable hosts.
     *
     * @param args command-line arguments (not used)
     * @throws Exception if scanning fails
     */
    public static void main(String[] args) throws Exception {
        int[] bounds = ScanIPS.rangeFromCidr("192.168.3.255/24");

        for (int i = bounds[0]; i <= bounds[1]; i++) {
            String address = InetRange.intToIp(i);
            InetAddress ip = InetAddress.getByName(address);

            if (ip.isReachable(100)) { // Try for one tenth of a second
                System.out.printf("Address %s is reachable\n", ip);
            }
        }
    }

    /**
     * Converts a CIDR notation IP address to an integer range.
     *
     * <p>This method parses CIDR notation (e.g., "192.168.1.0/24") and returns
     * the lower and upper bounds of the IP address range as integers.
     *
     * <p><b>Example:</b>
     * <pre>{@code
     * int[] range = rangeFromCidr("192.168.1.0/24");
     * // range[0] = lower bound (192.168.1.0 as int)
     * // range[1] = upper bound (192.168.1.255 as int)
     * }</pre>
     *
     * @param cidrIp IP address in CIDR notation (e.g., "192.168.1.0/24")
     * @return array of two integers: [0] = lower bound, [1] = upper bound
     * @throws NumberFormatException if CIDR notation is invalid
     * @see InetRange#ipToInt(String)
     * @see InetRange#intToIp(int)
     */
    public static int[] rangeFromCidr(String cidrIp) {
        int maskStub = 1 << 31;
        String[] atoms = cidrIp.split("/");
        int mask = Integer.parseInt(atoms[1]);
        System.out.println(mask);

        int[] result = new int[2];
        result[0] = InetRange.ipToInt(atoms[0]) & (maskStub >> (mask - 1)); // lower bound
        result[1] = InetRange.ipToInt(atoms[0]); // upper bound
        System.out.println(InetRange.intToIp(result[0]));
        System.out.println(InetRange.intToIp(result[1]));

        return result;
    }

    /**
     * Scans an IP address range in CIDR notation and returns all reachable hosts.
     *
     * <p>This method iterates through all IP addresses in the specified CIDR range,
     * tests each address for reachability using ICMP ping (100ms timeout), and
     * collects all responsive hosts into a vector.
     *
     * <p><b>Usage Example:</b>
     * <pre>{@code
     * ScanIPS scanner = new ScanIPS();
     * Vector<InetAddress> liveHosts = scanner.scanRange("10.0.1.0/24");
     * System.out.println("Found " + liveHosts.size() + " live hosts");
     * }</pre>
     *
     * <p><b>Performance Note:</b> This is a synchronous, sequential scan. For large ranges,
     * consider using parallel scanning or asynchronous I/O for better performance.
     *
     * @param text IP address range in CIDR notation (e.g., "192.168.1.0/24")
     * @return Vector containing all reachable InetAddress objects
     * @throws UnknownHostException if the IP address format is invalid
     * @throws IOException if network I/O errors occur during scanning
     * @see #rangeFromCidr(String)
     * @see InetAddress#isReachable(int)
     */
    public Vector<InetAddress> scanRange(String text) throws UnknownHostException, IOException {

        Vector<InetAddress> v = new Vector<InetAddress>();

        int[] bounds = ScanIPS.rangeFromCidr(text);

        for (int i = bounds[0]; i <= bounds[1]; i++) {
            String address = InetRange.intToIp(i);

            InetAddress ip = InetAddress.getByName(address);

            if (ip.isReachable(100)) { // Try for one tenth of a second
                System.out.printf("Address %s is reachable\n", ip);
                v.add(ip);
            }
        }

        return v;
    }

    /**
     * Utility class for converting between IP address representations.
     *
     * <p>This helper class provides methods to convert IP addresses between
     * dotted-decimal notation (e.g., "192.168.1.1") and 32-bit integer representation.
     *
     * <p><b>Why Integer Representation?</b>
     * <ul>
     *   <li>Enables efficient range calculations</li>
     *   <li>Simplifies CIDR network boundary computations</li>
     *   <li>Allows sequential iteration through IP ranges</li>
     * </ul>
     *
     * @see #ipToInt(String)
     * @see #intToIp(int)
     */
    static class InetRange {

        /**
         * Converts an IP address from dotted-decimal notation to a 32-bit integer.
         *
         * <p>This conversion allows IP addresses to be used in arithmetic operations
         * and range calculations. Each octet is shifted and combined using bitwise OR.
         *
         * <p><b>Example:</b>
         * <pre>{@code
         * int ip = InetRange.ipToInt("192.168.1.100");
         * // Returns: -1062731420 (signed int representation)
         * // Bit pattern: 11000000 10101000 00000001 01100100
         * }</pre>
         *
         * @param ipAddress IP address in dotted-decimal format (e.g., "192.168.1.1")
         * @return 32-bit integer representation of the IP address, or 0 if conversion fails
         */
        public static int ipToInt(String ipAddress) {
            try {
                byte[] bytes = InetAddress.getByName(ipAddress).getAddress();
                int octet1 = (bytes[0] & 0xFF) << 24;
                int octet2 = (bytes[1] & 0xFF) << 16;
                int octet3 = (bytes[2] & 0xFF) << 8;
                int octet4 = bytes[3] & 0xFF;
                int address = octet1 | octet2 | octet3 | octet4;

                return address;
            } catch (Exception e) {
                e.printStackTrace();
                return 0;
            }
        }

        /**
         * Converts a 32-bit integer to an IP address in dotted-decimal notation.
         *
         * <p>This is the inverse operation of {@link #ipToInt(String)}. It extracts
         * each octet using bitwise AND and unsigned right shift operations.
         *
         * <p><b>Example:</b>
         * <pre>{@code
         * String ip = InetRange.intToIp(-1062731420);
         * // Returns: "192.168.1.100"
         * }</pre>
         *
         * @param ipAddress 32-bit integer representation of an IP address
         * @return IP address in dotted-decimal format (e.g., "192.168.1.1")
         */
        public static String intToIp(int ipAddress) {
            int octet1 = (ipAddress & 0xFF000000) >>> 24;
            int octet2 = (ipAddress & 0xFF0000) >>> 16;
            int octet3 = (ipAddress & 0xFF00) >>> 8;
            int octet4 = ipAddress & 0xFF;

            return new StringBuffer().append(octet1).append('.').append(octet2)
                                     .append('.').append(octet3).append('.')
                                     .append(octet4).toString();
        }
    }
}