import assert from 'node:assert/strict';
import {test} from 'node:test';
import {getErrorMessage} from './roles.js';
test('timeout reports unknown write outcome instead of success or raw English',()=>{
 for(const code of ['ECONNABORTED','ETIMEDOUT']){
  const message=getErrorMessage({code,message:'timeout of 20000ms exceeded'});
  assert.match(message,/Chưa xác nhận thao tác đã được lưu/);assert.match(message,/tải lại thông tin trước khi thử lại/);assert.doesNotMatch(message,/20000ms|thành công/);
 }
});
test('server errors remain authoritative even when timeout code is attached',()=>{
 assert.equal(getErrorMessage({code:'ECONNABORTED',response:{data:{message:'Hồ sơ đã thay đổi.'}}}),'Hồ sơ đã thay đổi.');
 assert.equal(getErrorMessage({},'Không thể lưu GPLX.'),'Không thể lưu GPLX.');
});

test('network and unstructured server failures show actionable Vietnamese feedback',()=>{
 const network=getErrorMessage({code:'ERR_NETWORK',message:'Network Error'});
 assert.match(network,/Không thể kết nối máy chủ/);assert.match(network,/Chưa xác nhận/);
 for(const data of ['<html>SQL stack trace</html>',null]){
  const message=getErrorMessage({response:{status:500,data},message:'Request failed with status code 500'});
  assert.match(message,/Máy chủ gặp lỗi/);assert.doesNotMatch(message,/SQL|stack|Request failed|<html>/);
 }
 assert.equal(getErrorMessage({response:{status:409,data:{message:'Hồ sơ đã thay đổi.'}}}),'Hồ sơ đã thay đổi.');
 assert.equal(getErrorMessage({response:{status:500,data:{message:'Không thể tải dữ liệu.'}}}),'Không thể tải dữ liệu.');
});
