/*
 * Copyright jd
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import com.jd.bdp.hydra.mysql.persistent.dao.ServiceIdGenMapper;
import com.jd.bdp.hydra.mysql.persistent.entity.ServiceIdGen;
import com.jd.bdp.hydra.mysql.persistent.service.ServiceIdGenService;

/**
 * User: biandi
 * Date: 13-4-16
 * Time: 上午11:05
 */
@RunWith(SpringJUnit4ClassRunner.class) // 使用junit4进行测试
@ContextConfiguration(locations = { "classpath:hydra-manager-db.xml" })
public class TestServiceIdGenService //extends AbstractDependencyInjectionSpringContextTests 
{

    @Test
    public void testGetNewId(){
        String id1 = serviceIdGenService.getNewServiceId();
        String id2 = serviceIdGenService.getNewServiceId();
        ServiceIdGen serviceIdGen = serviceIdGenMapper.getServiceIdGen();
        int headLength = serviceIdGen.getMaxHead().toString().length();

        String id1Head = String.valueOf(id1).substring(0, headLength);
        String id2Head = String.valueOf(id2).substring(0, headLength);
        //首先比较head是否不同，且在遭遇最大值前自增
        if (Integer.parseInt(id2Head) > Integer.parseInt(id1Head)){
            assertTrue(true);
        }else if (Integer.parseInt(id2Head) < Integer.parseInt(id1Head)){
            assertEquals(id1Head, serviceIdGen.getMaxHead());
        }else {
            assertTrue(false);
        }
        //比较之后的几位不同
        int trueId1 = Integer.parseInt(String.valueOf(id1).substring(id1Head.length()));
        int trueId2 = Integer.parseInt(String.valueOf(id2).substring(id2Head.length()));
        assertTrue(trueId2 > trueId1);
    }


    @Test
    public void testServiceIdDifferent(){
        Map<String, String> map = new HashMap<String, String>();
        new Thread(new TestThread(map, serviceIdGenService)).run();
        new Thread(new TestThread(map, serviceIdGenService)).run();
        new Thread(new TestThread(map, serviceIdGenService)).run();
        assertEquals(150, map.size());
    }

    private class TestThread implements Runnable{

        private Map<String, String> map;
        private ServiceIdGenService serviceIdGenService;

        private TestThread(Map<String, String> map, ServiceIdGenService serviceIdGenService){
            this.map = map;
            this.serviceIdGenService = serviceIdGenService;
        }

        @Override
        public void run() {
            for (int i = 0; i < 50; i++) {
                String id = serviceIdGenService.getNewServiceId();
                map.put(id, id);
            }
        }
    }


   /* @Override
    protected String[] getConfigLocations() {
        String[] location = {"classpath:hydra-manager-db.xml"};
        return location;
    }*/

    private ServiceIdGenMapper serviceIdGenMapper;
    private ServiceIdGenService serviceIdGenService;

    public void setServiceIdGenService(ServiceIdGenService serviceIdGenService) {
        this.serviceIdGenService = serviceIdGenService;
    }

    public void setServiceIdGenMapper(ServiceIdGenMapper serviceIdGenMapper) {
        this.serviceIdGenMapper = serviceIdGenMapper;
    }
}
