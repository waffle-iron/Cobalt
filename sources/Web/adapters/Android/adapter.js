cobalt.android_adapter={
	//
	//ANDROID ADAPTER
	//
	init:function(){
		cobalt.platform="Android";
	},
	// handle events sent by native side
    handleEvent:function(json){
		cobalt.log("----received : "+JSON.stringify(json), false)
        if (cobalt.userEvents && typeof cobalt.userEvents[json.event] === "function"){
			cobalt.userEvents[json.event](json.data,json.callback);
	    }else{
	        switch (json.event){
		        case "onBackButtonPressed":
				    cobalt.log('sending OK for a native back')
			        cobalt.sendCallback(json.callback,{value : true});
			    break;
	        }
        }
    },
    //send native stuff
    send:function(obj){
        if (obj && cobalt.sendingToNative){
        	cobalt.log('----sending :'+JSON.stringify(obj), false)
	        try{	        	
		        Android.handleMessageSentByJavaScript(JSON.stringify(obj));
	        }catch (e){
		        cobalt.log('cant connect to native', false)
	        }

        }
    },
	//modal stuffs. really basic on ios, more complex on android.
	navigateToModal:function(page, controller){
		if ( cobalt.checkDependency('storage') ){
			cobalt.send({ "type":"navigation", "action":"modal", data : { page :page, controller: controller }}, 'cobalt.adapter.storeModalInformations');
		}
	},
	dismissFromModal:function(){
		if ( cobalt.checkDependency('storage') ){
			var dismissInformations= cobalt.storage.getItem("dismissInformations","json");
			if (dismissInformations && dismissInformations.page && dismissInformations.controller){
				cobalt.send({ "type":"navigation", "action":"dismiss", data : { page : dismissInformations.page, controller:dismissInformations.controller }});
				cobalt.storage.removeItem("dismissInformations");
			}else{
				cobalt.log("dismissInformations are not available in storage")
			}
		}


	},
	storeModalInformations:function(params){
		//cobalt.log("storing informations for the dismiss :", false)
		if ( cobalt.checkDependency('storage') ){
			cobalt.log(params, false)
			cobalt.storage.setItem("dismissInformations",params, "json")

		}
	},
	//localStorage stuff
	initStorage:function(){
		//on android, try to bind window.localStorage to Android LocalStorage
		try{
			window.localStorage=LocalStorage;
		}catch(e){
			cobalt.log("LocalStorage ERROR : can't find android class LocalStorage. switching to raw localStorage")
		}
		return cobalt.storage.enable();
	},
	//default behaviours
    handleCallback : cobalt.defaultBehaviors.handleCallback
};
cobalt.adapter=cobalt.android_adapter;