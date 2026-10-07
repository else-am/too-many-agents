// Preauthored before specialized-windows.mjs implementation. W01–W03/R01–R03
// in inventory-native-scenarios.md remain independent, lead-owned live checks.
export const scenarios = [
  ['furnace source properties', 'Actual pinned Furnace plugin for furnace/smoker/blast: ratios/seconds/totals, slot getters, put signatures, original Item take returns; native atomic zero properties and updates.'],
  ['enchantment source options', 'Actual pinned EnchantmentTable plugin for options/seed/ready and string choices; native result Item and XP/lapis barrier, creative without XP events, rejected/no-op button does not hang.'],
  ['anvil source and corrections', 'Actual pinned rename source signature/void result/name limit; native preserves input order, one final name including empty, authoritative cost/result/components, no Item.anvil prediction or creative XP wait.'],
  ['merchant source offers', 'Actual pinned Villager plugin parses real offer packets; compare source trade fields/realPrice/ready/returns. Native adjusted cost is not adjusted twice and selectedTrade updates from authoritative state.'],
  ['merchant exact operations', 'Native selection fills predicate-valid input components, one output pickup per operation, default/falsy times=remaining uses, second input, exhaustion/price change/restock and actual uses/XP only.'],
  ['specialized queue and failures', 'Whole operations serialize; same-menu known failure cleanup is bounded; unknown pickup/selection/name/button outcomes poison pending work; no implicit drop or event-only wait.'],
  ['specialized generation/events', 'Hydration updates methods/properties atomically before slot/open events; unchanged state does not repeat ready/update, close ends updates, stale window IDs/generations cannot mutate a replacement.'],
  ['specialized native pending', 'No claim native smelting/XP/enchantment/repair/trading works until lead independently runs W01–W03; controlled callbacks only establish guest orchestration.'],
];

import assert from 'node:assert/strict';
import { guest, registry, reference, make, encode, Item, windows, EventEmitter } from './inventory.mjs';
const id = name => registry.itemsByName[name].id;
const stone=id('stone'), coal=id('coal'), iron=id('iron_ingot'), sword=id('diamond_sword');
const lapis=id('lapis_lazuli'), emerald=id('emerald'), book=id('enchanted_book');
const block = `{position:new Vec3(1,2,3)}`;
const custom = name => [{type:'custom_name',data:{type:'string',value:name}}];
const tests=[];
const test=(name,run)=>tests.push([name,run]);
function sourceWindow(type, plugin) {
  const window=windows.createWindow(4,type,'Source');
  const calls=[];
  const bot=Object.assign(new EventEmitter(),{registry,version:'1.21.1',_client:new EventEmitter(),
    game:{gameMode:'creative'},experience:{level:30},supportFeature:key=>registry.supportFeature(key),
    openBlock:async()=>window,openEntity:async()=>window,
    putAway:async slot=>{calls.push(['putAway',slot]);},
    moveSlotItem:async(...args)=>{calls.push(['moveSlotItem',...args]);},
    transfer:async options=>{calls.push(['transfer',{...options,window:undefined}]);},
  });
  bot._client.write=(...args)=>calls.push(['packet',...args]);
  window.close=()=>{};
  reference(`mineflayer/lib/plugins/${plugin}`)(bot,{version:'1.21.1'});
  return {bot,window,calls,property:(property,value)=>bot._client.emit('craft_progress_bar',{windowId:4,property,value})};
}
test('furnace source properties',async()=>{
  for(const type of ['minecraft:furnace','minecraft:smoker','minecraft:blast_furnace']) {
    const source=sourceWindow(type,'furnace'), window=await source.bot.openFurnace({});
    source.property(1,1600); source.property(0,800); source.property(3,200); source.property(2,50);
    window.updateSlot(2,make({type:iron,count:2})); const original=window.outputItem();
    assert.equal(await window.takeOutput(),original); await window.putInput(stone,null,3); await window.putFuel(coal,null,2);
    assert.deepEqual(source.calls.map(c=>c[0]),['putAway','transfer','transfer']);
    const actual=await guest({openType:type,inventory:[[9,{type:stone,count:3}],[10,{type:coal,count:2}]],observation:()=>({properties:[800,1600,50,200]}),
      after:({args,window})=>{if(args.type==='interact')window.updateSlot(2,make({type:iron,count:2}));}},`
      const w=await bot.openFurnace(${block}); const item=w.outputItem(); const taken=await w.takeOutput();
      const input=await w.putInput(${stone},null,3),fuel=await w.putFuel(${coal},null,2);
      return {same:taken===item,putVoid:input===undefined&&fuel===undefined,counts:[w.inputItem().count,w.fuelItem().count],fields:[w.fuel,w.progress,w.fuelSeconds,w.progressSeconds,w.totalFuelSeconds,w.totalProgressSeconds]};`);
    assert.equal(actual.value.same,true);
    assert.equal(actual.value.putVoid,true);assert.deepEqual(actual.value.counts,[3,2]);
    assert.deepEqual(actual.value.fields,[window.fuel,window.progress,window.fuelSeconds,window.progressSeconds,window.totalFuelSeconds,window.totalProgressSeconds]);
    assert(actual.native.calls.every(a=>a.clickType!=='QUICK_MOVE'));
  }
  const zero=await guest({openType:'minecraft:furnace',observation:()=>({properties:[0,0,0,0]})},`
    const w=await bot.openFurnace(${block}); return [w.fuel,w.progress,w.fuelSeconds,w.progressSeconds];`);
  assert.deepEqual(zero.value,[0,0,0,0]);
});
test('enchantment source options',async()=>{
  const source=sourceWindow('minecraft:enchantment','enchantment_table');
  const window=await source.bot.openEnchantmentTable({name:'enchanting_table'}); let ready=0; window.on('ready',()=>ready++);
  const properties=[3,7,30,1232,0,1,2,1,2,3]; properties.forEach((v,i)=>source.property(i,v));
  window.updateSlot(0,make({type:sword,count:1}));
  source.bot._client.write=(name,args)=>{source.calls.push([name,args]);setImmediate(()=>window.updateSlot(0,make({type:sword,count:1,components:custom('enchanted')})));};
  const expected=await window.enchant('1'); assert.equal(expected.type,sword); assert.equal(ready,1);
  const spec={openType:'minecraft:enchantment',experience:{level:30,progress:0,total:500,seed:1232},observation:()=>({properties}),
    after:({args,window})=>{
      if(args.type==='interact'){window.updateSlot(0,make({type:sword,count:1}));window.updateSlot(1,make({type:lapis,count:3}));}
      if(args.type==='menu_button'){window.updateSlot(0,make({type:sword,count:1,components:custom('enchanted')}));window.updateSlot(1,make({type:lapis,count:1}));spec.experience.level=28;}
    }};
  const actual=await guest(spec,`
    const w=await bot.openEnchantmentTable({...${block},name:'enchanting_table'});
    const options=w.enchantments, seed=w.xpseed; const enchanted=await w.enchant('1');
    return {options,seed,same:enchanted===w.targetItem(),level:bot.experience.level,lapis:w.slots[1].count};`);
  assert.deepEqual(actual.value.options,window.enchantments); assert.equal(actual.value.seed,window.xpseed);
  assert.equal(actual.value.same,true); assert.equal(actual.value.level,28); assert.equal(actual.value.lapis,1);
  const noop=await guest({container:'minecraft:enchantment',slots:[[0,{type:sword,count:1}]],observation:()=>({properties})},`
    return await caught(()=>bot.currentWindow.enchant(0));`);
  assert.equal(noop.value.code,'NoProgress'); assert.equal(noop.native.calls.length,1);
});
function anvilSpec(extra={}) {
  let name;
  return {container:'minecraft:anvil',inventory:[[9,{type:sword,count:1}],[10,{type:book,count:1}]],
    observation:()=>({properties:[2]}),
    after:({args,window})=>{
      if(args.type==='anvil_name') {name=args.name; window.updateSlot(2,make({type:sword,count:1,components:name?custom(name):[]}));}
      if(args.type==='menu_click'&&args.slot===2&&window.selectedItem){window.updateSlot(0,null);window.updateSlot(1,null);}
    },...extra};
}
test('anvil source and corrections',async()=>{
  const source=sourceWindow('minecraft:anvil','anvil'); const window=await source.bot.openAnvil({});
  source.bot.putAway=async slot=>{source.calls.push(['putAway',slot]); source.bot.emit('experience');};
  const item=make({type:sword,count:1}); assert.equal(await window.rename(item,'A'),undefined);
  assert(source.calls.some(c=>c[0]==='packet'&&c[1]==='name_item'&&c[2].name==='A'));
  const actual=await guest(anvilSpec(),`
    const w=bot.currentWindow; const result=await w.rename(bot.inventory.slots[9],'A');
    return {returned:result===undefined,state:summarize()};`);
  assert.equal(actual.value.returned,true); assert.equal(actual.value.state.cursor,0);
  assert.deepEqual(actual.native.calls.filter(a=>a.type==='anvil_name').map(a=>a.name),['A']);
  const cleared=await guest(anvilSpec(),`await bot.currentWindow.combine(bot.inventory.slots[9],bot.inventory.slots[10],''); return summarize();`);
  assert.deepEqual(cleared.native.calls.filter(a=>a.type==='anvil_name').map(a=>a.name),['']);
  const puts=cleared.native.calls.filter(a=>a.type==='menu_click').map(a=>a.slot);
  assert.deepEqual(puts.slice(0,4),[3,0,4,1]); // Caller order, no simulated cost-based reversal.
  const denied=await guest(anvilSpec({rules:{2:{mayPickup:false}}}),`
    const error=await caught(()=>bot.currentWindow.rename(bot.inventory.slots[9],'A')); return {error,state:summarize()};`);
  assert.equal(denied.value.error.code,'CannotPickup'); assert.equal(denied.value.state.slots[0],null);
  assert.equal(denied.native.dropped.length,0);
  const long=await guest(anvilSpec(),`return await caught(()=>bot.currentWindow.rename(bot.inventory.slots[9],'x'.repeat(36)));`);
  assert.equal(long.value.code,'InvalidName'); assert.equal(long.native.calls.length,0);
});
const offer=()=>({baseCostA:encode(make({type:emerald,count:2})),costA:encode(make({type:emerald,count:3})),costB:null,
  result:encode(make({type:book,count:1})),uses:0,maxUses:2,demand:5,specialPrice:-1,priceMultiplier:.2,xp:3,outOfStock:false,rewardExp:true});
function merchantSpec(extra={}) {
  const value=offer(); let selected=0;
  const spec={container:'minecraft:merchant',inventory:[[9,{type:emerald,count:6}]],experience:{level:0,progress:0,total:0,seed:0},
    observation:()=>({merchant:{offers:[value],selectedTrade:selected,xp:value.uses*3}}),
    before:({args,window})=>{
      if(args.type==='select_trade') {
        selected=args.index;
        if(!window.slots[0]){window.updateSlot(0,make(encode(window.slots[3])));window.updateSlot(3,null);}
        window.updateSlot(2,window.slots[0]?.count>=value.costA.count&&!value.outOfStock?make(value.result):null);
        return {skip:true};
      }
    },
    after:({args,window})=>{
      if(args.type==='menu_click'&&args.slot===2&&window.selectedItem) {
        const count=window.slots[0].count-value.costA.count;
        window.updateSlot(0,count?make({...encode(window.slots[0]),count}):null);
        value.uses++; value.outOfStock=value.uses===value.maxUses; spec.experience.total+=3;
      }
    },...extra};
  return {spec,value};
}
test('merchant source offers',async()=>{
  const source=sourceWindow('minecraft:merchant','villager');
  const promise=source.bot.openVillager({entityType:registry.entitiesByName.villager.id});
  const v=offer();
  setImmediate(()=>source.bot._client.emit('trade_list',{windowId:4,trades:[{
    inputItem1:Item.toNotch(make(v.baseCostA)),inputItem2:Item.toNotch(null),outputItem:Item.toNotch(make(v.result)),
    nbTradeUses:0,maximumNbTradeUses:2,tradeDisabled:false,demand:5,specialPrice:-1,priceMultiplier:.2,xp:3}]}));
  const window=await promise;
  const {spec}=merchantSpec();
  const actual=await guest(spec,`const w=bot.currentWindow,t=w.trades[0]; return {type:t.inputItem1.type,base:t.inputItem1.count,price:t.realPrice,has:t.hasItem2,uses:t.nbTradeUses,max:t.maximumNbTradeUses,selected:w.selectedTrade===t};`);
  const expected=window.trades[0];
  assert.deepEqual(actual.value,{type:expected.inputItem1.type,base:expected.inputItem1.count,price:expected.realPrice,has:expected.hasItem2,uses:expected.nbTradeUses,max:expected.maximumNbTradeUses,selected:true});
  window.updateSlot(window.inventoryStart,make({type:emerald,count:6}));
  source.bot._client.write=(name,args)=>{
    source.calls.push(['packet',name,args]);
    if(name==='select_trade') setImmediate(()=>{
      window.updateSlot(0,make({type:emerald,count:3})); window.updateSlot(2,make({type:book,count:1}));
    });
  };
  let usesBeforeNativeTake;
  source.bot.putAway=async slot=>{
    if(slot===2){usesBeforeNativeTake=window.trades[0].nbTradeUses;window.updateSlot(0,null);window.updateSlot(2,null);}
  };
  assert.equal(await window.trade('0',1),undefined);
  // Actual pinned source increments uses before asking for a result pickup.
  // The guest correction waits for native use counts after that pickup.
  assert.equal(usesBeforeNativeTake,1);
  // The native cost is authoritative even when a subsequent demand formula
  // would disagree; no second adjustment is applied to costA.
  v.costA.count=1;
  const changed=await guest({container:'minecraft:merchant',observation:()=>({merchant:{offers:[v],selectedTrade:0}})},`return bot.currentWindow.trades[0].realPrice;`);
  assert.equal(changed.value,1);
});
test('merchant exact operations',async()=>{
  for(const times of ['undefined','0','1']) {
    const {spec}=merchantSpec();
    const actual=await guest(spec,`
      const w=bot.currentWindow,t=w.trades[0]; const returned=await w.trade('0',${times});
      return {returned:returned===undefined,uses:t.nbTradeUses,disabled:t.tradeDisabled,xp:bot.experience.points,state:summarize()};`);
    const expected=times==='1'?1:2;
    assert.equal(actual.value.returned,true); assert.equal(actual.value.uses,expected); assert.equal(actual.value.xp,expected*3);
    assert.equal(actual.native.calls.filter(a=>a.type==='menu_click'&&a.slot===2).length,expected);
    assert(actual.native.calls.every(a=>a.clickType!=='QUICK_MOVE')); assert.equal(actual.value.state.cursor,0);
    assert.equal(actual.value.state.slots[0],null); assert.equal(actual.native.dropped.length,0);
  }
  const {spec,value}=merchantSpec(); value.outOfStock=true;
  const exhausted=await guest(spec,`return await caught(()=>bot.currentWindow.trade(0,1));`);
  assert.equal(exhausted.value.code,'InvalidTradeCount'); assert.equal(exhausted.native.calls.length,0);
  const dual=merchantSpec(); const diamond=id('diamond');
  dual.value.costB=encode(make({type:diamond,count:1})); dual.spec.inventory.push([10,{type:diamond,count:2}]);
  const beforeDual=dual.spec.before,afterDual=dual.spec.after;
  dual.spec.before=context=>{
    const result=beforeDual(context);
    if(context.args.type==='select_trade'&&!context.window.slots[1]) {
      context.window.updateSlot(1,make(encode(context.window.slots[4])));context.window.updateSlot(4,null);
    }
    return result;
  };
  dual.spec.after=context=>{
    afterDual(context);
    if(context.args.type==='menu_click'&&context.args.slot===2) {
      const remaining=context.window.slots[1].count-1;
      context.window.updateSlot(1,remaining?make({type:diamond,count:remaining}):null);
      if(dual.value.uses===1)dual.value.costA.count=2;
    }
  };
  const second=await guest(dual.spec,`await bot.currentWindow.trade(0,2);return {state:summarize(),price:bot.currentWindow.trades[0].realPrice};`);
  assert.equal(second.value.price,2); assert.equal(second.value.state.slots[0],null); assert.equal(second.value.state.slots[1],null);
  assert.equal(second.value.state.inventory.reduce((n,i)=>n+(i?.[0]===emerald?i[1]:0),0),1);
  assert.equal(second.value.state.inventory.reduce((n,i)=>n+(i?.[0]===diamond?i[1]:0),0),0);
  const components=merchantSpec();
  components.value.baseCostA.components=components.value.costA.components=custom('accepted');
  components.spec.inventory=[[9,{type:emerald,count:6,components:custom('rejected')}],[10,{type:emerald,count:6,components:custom('accepted')}]];
  components.spec.before=({args,window})=>{
    if(args.type==='select_trade'){
      window.updateSlot(0,make(encode(window.slots[4])));window.updateSlot(4,null);window.updateSlot(2,make(components.value.result));return {skip:true};
    }
  };
  const predicate=await guest(components.spec,`await bot.currentWindow.trade(0,1);return {count:bot.inventory.slots[9].count,components:bot.inventory.slots[9].components};`);
  assert.deepEqual(predicate.value,{count:6,components:custom('rejected')});
  const restock=merchantSpec(); restock.value.uses=2; restock.value.outOfStock=true;
  const afterRestock=restock.spec.after;
  restock.spec.after=context=>{afterRestock(context);if(context.args.slot===20){restock.value.uses=0;restock.value.outOfStock=false;}};
  const stocked=await guest(restock.spec,`const w=bot.currentWindow,trade=w.trades[0];await bot.clickWindow(20,0,0);await w.trade(0,1);return {same:trade===w.trades[0],uses:trade.nbTradeUses,disabled:trade.tradeDisabled};`);
  assert.deepEqual(stocked.value,{same:true,uses:1,disabled:false});
  // MerchantMenu.tryMoveItems fills A to stack capacity before considering B.
  // A and B may accept the same item: native selection alone can starve B even
  // though there are enough payments. Split a bounded surplus, then require the
  // actual native result before consuming either input.
  const shared=merchantSpec(); shared.value.costB=encode(make({type:emerald,count:1}));
  shared.spec.before=({args,window})=>{
    if(args.type==='select_trade') {window.updateSlot(0,make(encode(window.slots[3])));window.updateSlot(3,null);window.updateSlot(2,null);return {skip:true};}
  };
  shared.spec.after=({args,window})=>{
    if(args.type==='menu_click'&&args.slot===2&&window.selectedItem){
      const count=window.slots[0].count-3;window.updateSlot(0,count?make({type:emerald,count}):null);
      window.updateSlot(1,null);shared.value.uses++;
    }
    const payable=window.slots[0]?.type===emerald&&window.slots[0].count>=3&&window.slots[1]?.type===emerald&&window.slots[1].count>=1;
    window.updateSlot(2,payable?make(shared.value.result):null);
  };
  const overlap=await guest(shared.spec,`await bot.currentWindow.trade(0,1);return summarize();`);
  assert.equal(overlap.value.inventory.reduce((n,i)=>n+(i?.[0]===emerald?i[1]:0),0),2);
});
test('specialized queue and failures',async()=>{
  const {spec}=merchantSpec(); const after=spec.after;
  spec.after=context=>{after(context);if(context.args.type==='menu_click'&&context.args.slot===2)return {error:{message:'lost result reply'}};};
  const unknown=await guest(spec,`
    const results=await Promise.allSettled([bot.currentWindow.trade(0,1),bot.clickWindow(5,0,0)]); return results.map(r=>r.status);`);
  assert.deepEqual(unknown.value,['rejected','rejected']); assert.equal(unknown.native.calls.at(-1).slot,2);
  const known=await guest(anvilSpec({before:({args})=>args.type==='anvil_name'?{error:{known:true,message:'name denied'}}:undefined}),`
    const error=await caught(()=>bot.currentWindow.rename(bot.inventory.slots[9],'A')); return {error,state:summarize()};`);
  assert.equal(known.value.error.message,'name denied'); assert.equal(known.value.state.slots[0],null);
  const lostName=await guest(anvilSpec({after:({args})=>args.type==='anvil_name'?{error:{message:'lost name reply'}}:undefined}),`
    const results=await Promise.allSettled([bot.currentWindow.rename(bot.inventory.slots[9],'A'),bot.clickWindow(6,0,0)]);return results.map(r=>r.status);`);
  assert.deepEqual(lostName.value,['rejected','rejected']);assert.equal(lostName.native.calls.at(-1).type,'anvil_name');
  const queued=await guest(anvilSpec(),`
    await Promise.all([bot.currentWindow.rename(bot.inventory.slots[9],'A'),bot.clickWindow(6,0,0)]); return summarize();`);
  assert.equal(queued.native.calls.at(-1).slot,6);
});
test('specialized generation/events',async()=>{
  let properties=[0,0,0,0]; let count=0;
  const actual=await guest({container:'minecraft:furnace',observation:()=>({properties}),
    after:()=>{if(++count===2)properties=[100,200,50,200];}},`
    const w=bot.currentWindow,updates=[]; w.on('update',()=>updates.push([w.fuel,w.progress,w.fuelSeconds]));
    await bot.clickWindow(3,0,0); await bot.clickWindow(3,0,0); await bot.clickWindow(3,0,0);
    await w.close(); const stale=await caught(()=>w.takeOutput()); return {updates,stale};`);
  assert.deepEqual(actual.value.updates,[[.5,.25,5]]); assert.equal(actual.value.stale.code,'WindowChanged');
  const wrong=await guest({openType:'minecraft:generic_9x3'},`return await caught(()=>bot.openFurnace(${block}));`);
  assert.equal(wrong.value.code,'UnexpectedWindow');
  const initial=await guest({openType:'minecraft:furnace',observation:()=>({properties:[0,0,0,0]})},`
    let observed;bot.on('windowOpen',w=>observed=[typeof w.takeOutput,w.fuel,w.progress,w instanceof Window]);
    await bot.openFurnace(${block});return observed;`);
  assert.deepEqual(initial.value,['function',0,0,true]);
  let options=new Array(10).fill(-1);
  const ready=await guest({container:'minecraft:enchantment',observation:()=>({properties:options}),
    after:()=>{options=[3,7,30,1008,0,1,2,1,2,3];}},`
    const w=bot.currentWindow,events=[];w.on('ready',()=>events.push([w.xpseed,w.enchantments.map(e=>e.level)]));
    await bot.clickWindow(2,0,0);await bot.clickWindow(2,0,0);await w.close();return events;`);
  assert.deepEqual(ready.value,[[1008,[3,7,30]]]);
});

const results=[];
for(const [name,run] of tests) {
  try{await run();results.push({name,status:'passed'});console.log(`PASS ${name}`);}
  catch(error){results.push({name,status:'failed',error:error.stack});console.error(`FAIL ${name}: ${error.stack}`);}
}
const failed=results.filter(r=>r.status==='failed').length;
console.log(JSON.stringify({passed:results.length-failed,failed,preauthored:scenarios,results,
  runtime:{memory:64*1024*1024,stack:512*1024},evidence:'Actual pinned plugin comparisons and browser-bundled QuickJS with controlled callbacks; native W01–W03 remain pending.'},null,2));
process.exitCode=failed?1:0;
