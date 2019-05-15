PVA Client
==========

Exploration of a PV Access client for Java,
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


Command-line Example
--------------------

Start the example database: 

    softIocPVA -d src/test/resources/demo.db 

Then access it via

    ./pvaclient
    ./pvaclient info ramp
    ./pvaclient get ramp saw
    ./pvaclient put ramp 5
    ./pvaclient monitor ramp rnd
    



Implementation Status
---------------------

 * PVA Client:
   Maintains pool of PVs,
   registers new PVs with ChannelSearch,
   creates TCPHandler when channel found.
 * ChannelSearch: Exponential backup to ~30 seconds
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
   
TODO:

 * Monitor re-subscription
 * Echo test when no new data for a while
 * Handle all the data types: Fixed size or bounded arrays
 