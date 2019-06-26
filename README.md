PVA Client
==========

Note that this code is now in Phoebus:

    git clone https://github.com/shroffk/phoebus.git
    cd phoebus/core/pva/
    ant clean core-pva

This repo was used for the original exploration of a PV Access client for Java,
developed from scratch based on the 
[PV Access Protocol Description](https://github.com/epics-base/pvAccessCPP/wiki/protocol),
consulting the [Reference Implementation](https://github.com/epics-base/epicsCoreJava)
to clarify details.
Purpose is to better understand the protocol
and to implement it in pure Java, using concurrent classes
from the standard library and taking advantage of for example
functional interfaces because compatibility to the C++ implementation
is not required.
Implementation is focused on the requirements of clients like CS-Studio,
covering the majority of PV Access features but not all of them.

Build
-----

Prerequisites: `JAVA_HOME` set to JDK 9 or higher

    ant clean jar

Configuration
-------------

`EPICS_PVA_ADDR_LIST` can be set as environment variable
or Java property, see `PVASettings` source code.

Firewall
--------

Needs UDP access to port 5076, then TCP access to 5075 for first server.

To debug connection issues on Linux:

    sudo systemctl stop firewalld

To enable access to the first PVA server on a Linux host and list result:

    sudo firewall-cmd --direct --add-rule ipv4 filter IN_public_allow 0 -m udp -p udp --dport 5076 -j ACCEPT
    sudo firewall-cmd --direct --add-rule ipv4 filter IN_public_allow 0 -m tcp -p tcp --dport 5075 -j ACCEPT
    sudo firewall-cmd --direct --get-rules ipv4 filter IN_public_allow
    
Use `--remove-rule` to revert, add `--permanent` to persist the setting over firewall restarts.

When running more than one PVA server on a host, these use an unpredictable TCP port,
so firewall needs to allow all TCP access.
        
Command-line Example
--------------------

Start the example database: 

    softIocPVA -m N='' -d src/test/resources/demo.db 

Then access it via

    ./pvaclient
    ./pvaclient info ramp
    ./pvaclient get ramp saw
    ./pvaclient put ramp 5
    ./pvaclient monitor ramp rnd

To test the server, run ServerDemo and then access it via

    pvinfo demo
    pvget demo
    pvmonitor demo

Implementation Status
---------------------

PVA Client:

 * PVA Server list
 * Maintains pool of PVs
 * Registers new PVs with ChannelSearch
 * ChannelSearch: Exponential backup to ~30 seconds
 * Forward unicast searches to local multicast group
 * Creates TCPHandler when channel found.
 * Support "anonymous" or "ca"
   (with user from "user.name" property and host from InetAddress.getLocalHost().getHostName())
 * Echo test when no new data for a while,
   supporting both V1 (no content) and V2 (testing for matching content in reply)
 * Reset channel to search when TCP connection closed
 * Monitor beacons, boost search for missing channels
 * CreateChannelRequest
 * ChannelListener for notification about connect, disconnect
 * Get: Init, get structure, get value, destroy
 * Monitor: Init, get structure, subscribe, get changes, stop/destroy
 * Put: Init, get structure, update field, write, destroy
 * Decode data sent by IOC and 'image' demo
 * Close (destroy) channel
 * Close client
 * Info/get/monitor/put command line tool
 
PVA Server:

 * Maintains pool of PVs
 * Responds to searches
 * Forward unicast searches to local multicast group
 * Reply to 'list' search with GUID
 * Reply to 'get'
 * Support 'monitor'
   
TODO:

 * Testing
 * Handle all the data types: Fixed size or bounded arrays
