本项目是[Snova](http://code.google.com/p/snova/)的C4服务端的NodeJS实现， 可以部署到一些NodeJS PAaS平台，或者VPS上。

部署服务端
--------
> 目前支持NodeJS的有Heroku/Cloundfoudry/Openshift/Dotcloud/Appfog等，参考这些PaaS提供商官方文档部署
> 服务端实现可在github中下载，或者到googlecode上下载
> 部署到VPS上参考[C4VPSInstallation](http://code.google.com/p/snova/wiki/C4VPSInstallation)
>[下载](http://code.google.com/p/snova/downloads/list)


安装客户端
--------
>客户端为gsnova zip包，解压即可；目前预编译支持的有Windows（32/64位）， Linux（64位），Mac（64位）。   
用户按照[配置]一节修改配置后，即可启动gsnova。 windows用户直接执行gsnova.exe即可，Linux/Mac用户需要在命令行下启动gsnova程序。   
用户还需要修改浏览器的代理地址为127.0.0.1:48100， 或者在支持PAC设置的浏览器中设置PAC地址为http://127.0.0.1:48100/pac/gfwlist       
[下载](https://github.com/yinqiwen/gsnova/downloads)

配置
-------
主要需要修改gsnova.conf，以下针对各个PaaS平台部署后配置说明   

#####C4 
修改gsnova.conf中[C4]以下部分，默认Enable为0，开启需要修改Enable为1：   

    [C4]   
    Enable=1   
    WorkerNode[0]=myapp.cloudfoundry.com   
将申请的域名填入WorkerNode[0]=后，注意必须为域名，且在配置前请确保在浏览器中输入此域名能看到‘snova’相关信息（证明部署服务端成功且能直接访问）。若有多个，可以如下配置多个：

    [C4]   
    Enable=1   
    WorkerNode[0]=myapp1.cloudfoundry.com
    WorkerNode[1]=myapp2.cloudfoundry.com

域名前可以增加scheme指示连接协议类型， 默认为http， gsnova支持如下几种协议：  

    WorkerNode[0]=http://myapp1.cloudfoundry.com   
    WorkerNode[0]=https://myapp1.cloudfoundry.com
    WorkerNode[0]=ws://myapp1.cloudfoundry.com         //websocket， 服务端仅nodejs支持
    WorkerNode[0]=wss://myapp1.cloudfoundry.com        //websocket over ssl， 服务端仅nodejs支持

此Proxy实现在SPAC中名称为C4, 若想只用C4作为唯一的Proxy实现，修改[SPAC]下的Default值为C4或者更改代理默认端口为48102

