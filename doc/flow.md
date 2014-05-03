 +-----------------------------------------+
 |                                         |
 |    Get changed clj/cljs source files    |
 |                                         |
 +-------------------+---------------------+
                     |                      
                     |                      
  +------------------+--------------------+  
  |                                       |  
  |        Intersect with changed         |  
  |                                       |  
  |       compiled javascript files       |  
  |                                       |  
  +------------------+--------------------+  
                     |                      
                     |                      
  +------------------+--------------------+  
  |                                       |  
  |   ConVert to browser relative paths   |  
  |                                       |  
  +------------------+--------------------+  
                     |                      
                     |                      
+--------------------+----------------------+
|                                           |
|    If compiled dependency files changed   |
|                                           |
| add browser relative paths for dependency |
|                                           |
|                   files                   |
|                                           |
+---------------------+---------------------+
                      |                      
                      |                      
  +-------------------+-------------------+  
  |                                       |  
  |        Send this list of files        |  
  |                                       |  
  |    over a WebSocket to the browser    |  
  |                                       |  
  +------------------+--------------------+  
                     |                      
                     |                      
+--------------------+---------------------+
|                                          |
|    Browser client checks each file to    |
|                                          |
|     see if it is currently required      |
|                                          |
|          If so it loads it and           |
|                                          |
|       any unprovided dependancies        |
|                                          |
+--------------------+---------------------+
                     |                      
                     |                      
+--------------------+---------------------+
|                                          |
|  After all files have loaded the client  |
|                                          |
|   triggers a callback to allow app to    |
|                                          |
|                re-render                 |
|                                          |
+------------------------------------------+
