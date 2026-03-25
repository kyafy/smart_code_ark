import { PrismaClient } from '@prisma/client'

const prisma = new PrismaClient()

async function main() {
  await prisma.todo.deleteMany()

  await prisma.todo.createMany({
    data: [
      {
        title: '完成模板初始化',
        description: '确保 Next.js、Prisma 和 MySQL 可以正常协作'
      },
      {
        title: '扩展业务模块',
        description: '把任务管理替换成你的真实业务领域'
      }
    ]
  })
}

main()
  .then(async () => {
    await prisma.$disconnect()
  })
  .catch(async (error) => {
    console.error(error)
    await prisma.$disconnect()
    process.exit(1)
  })
