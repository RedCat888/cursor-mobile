"use strict";
// cursor-mobile-bridge-bootstrap v0.2.1
const path = require("path");
const bridge = require(path.join(__dirname, "cursor-mobile-bridge.js"));
const original = require(path.join(__dirname, "extension.original.js"));

exports.activate = (context) => {
	if (typeof original.activate === "function") {
		original.activate(context);
	}
	bridge.activate(context);
};

exports.deactivate = () => {
	if (typeof bridge.deactivate === "function") {
		bridge.deactivate();
	}
	if (typeof original.deactivate === "function") {
		original.deactivate();
	}
};
