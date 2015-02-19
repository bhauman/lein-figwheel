try {
    require("source-map-support").install();
} catch(err) {
}
require("./server_out/goog/bootstrap/nodejs.js");
require("./server_out/todo_server.js");
goog.require("todo_server.core");
goog.require("cljs.nodejscli");
