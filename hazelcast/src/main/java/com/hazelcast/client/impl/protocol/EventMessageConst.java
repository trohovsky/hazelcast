/*
 * Copyright (c) 2008-2015, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.client.impl.protocol;


/**
 * Message type ids of event responses in client protocol. They also used to bind a request to event inside Request
 * annotation.
 * <p/>
 * Event response classes are defined    {@link com.hazelcast.client.impl.protocol.template.EventResponseTemplate}
 * <p/>
 * see {@link   com.hazelcast.client.impl.protocol.template.ClientMessageTemplate#membershipListener()}
 * for  a sample usage of events in a request.
 */
public final class EventMessageConst {

    public static final int EVENT_MEMBER = 200;
    public static final int EVENT_MEMBERLIST = 201;
    public static final int EVENT_MEMBERATTRIBUTECHANGE = 202;

    public static final int EVENT_ENTRYEVENT = 203;
    public static final int EVENT_ITEMEVENT = 204;
    public static final int EVENT_TOPICEVENT = 205;
    public static final int EVENT_PARTITIONLOSTEVENT = 206;


    public static final int EVENT_DISTRIBUTEDOBJECT = 207;

}
